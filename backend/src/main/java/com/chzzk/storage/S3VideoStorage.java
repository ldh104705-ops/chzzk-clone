package com.chzzk.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores videos as objects in an S3-compatible bucket ({@code app.storage.type=s3}): MinIO in the local kind
 * cluster, Cloudflare R2 (or AWS S3) in production. Because the bytes live outside every backend instance, any
 * instance can serve any video, and scaling in or out never loses an upload.
 *
 * <p>HTTP Range support: {@link #load} returns a resource whose stream is "lazy-seeking". Spring MVC answers a
 * {@code Range: bytes=a-b} request by skipping {@code a} bytes of the resource stream and copying the range; this
 * stream turns that skip into a ranged {@code GetObject} ({@code Range: bytes=a-}) on the first read, so only the
 * requested part is fetched from the bucket and the response is a proper 206 with Content-Range.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3VideoStorage implements VideoStorage {

    private static final Logger log = LoggerFactory.getLogger(S3VideoStorage.class);
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");
    private static final Pattern STORED_NAME = Pattern.compile("[0-9a-f-]{36}(\\.[a-z0-9]{1,8})?");

    private final S3Client s3;
    private final String bucket;

    public S3VideoStorage(
            @Value("${app.storage.s3.endpoint:}") String endpoint,
            @Value("${app.storage.s3.region:us-east-1}") String region,
            @Value("${app.storage.s3.bucket:chzzk-videos}") String bucket,
            @Value("${app.storage.s3.access-key:}") String accessKey,
            @Value("${app.storage.s3.secret-key:}") String secretKey,
            @Value("${app.storage.s3.path-style:true}") boolean pathStyle,
            @Value("${app.storage.s3.create-bucket:false}") boolean createBucket) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "APP_STORAGE_S3_ACCESS_KEY and APP_STORAGE_S3_SECRET_KEY must be set when APP_STORAGE_TYPE=s3");
        }
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                // R2 and MinIO do not accept the SDK's newer default CRC checksums on every request.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.s3 = builder.build();
        this.bucket = bucket;
        if (createBucket) {
            ensureBucket();
        }
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            create();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                create();
            } else {
                throw e;
            }
        }
    }

    private void create() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created S3 bucket {}", bucket);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("Bucket {} was created concurrently by another instance", bucket);
        }
    }

    @Override
    public StoredVideo store(InputStream content, String originalName, String contentType) {
        String storedName = UUID.randomUUID() + extensionOf(originalName);
        Path spool = null;
        try {
            // PutObject needs the length up front (R2 insists on it), so spool the stream to a temp file first.
            spool = Files.createTempFile("chzzk-upload-", ".tmp");
            long size = Files.copy(content, spool, StandardCopyOption.REPLACE_EXISTING);
            PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(storedName);
            if (contentType != null && !contentType.isBlank()) {
                request.contentType(contentType);
            }
            s3.putObject(request.build(), RequestBody.fromFile(spool));
            return new StoredVideo(storedName, originalName, contentType, size);
        } catch (IOException e) {
            throw new UncheckedIOException("영상을 저장하지 못했습니다.", e);
        } finally {
            if (spool != null) {
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException e) {
                    log.debug("Could not delete temp file {}", spool, e);
                }
            }
        }
    }

    @Override
    public Resource load(String storedName) {
        String key = validKey(storedName);
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new S3Resource(key, head.contentLength(), head.lastModified());
        } catch (NoSuchKeyException e) {
            throw new VideoNotFoundException("저장된 영상 파일을 찾을 수 없습니다.");
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new VideoNotFoundException("저장된 영상 파일을 찾을 수 없습니다.");
            }
            throw e;
        }
    }

    @Override
    public void delete(String storedName) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(validKey(storedName)).build()); // idempotent in S3
    }

    private static String validKey(String storedName) {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            throw new VideoNotFoundException("잘못된 파일 이름입니다.");
        }
        return storedName;
    }

    private static String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(ext).matches() ? "." + ext : "";
    }

    /** An object in the bucket, seen by Spring MVC as a resource of known length. */
    private final class S3Resource extends AbstractResource {

        private final String key;
        private final long length;
        private final Instant lastModified;

        S3Resource(String key, long length, Instant lastModified) {
            this.key = key;
            this.length = length;
            this.lastModified = lastModified;
        }

        @Override
        public String getDescription() {
            return "S3 object [" + bucket + "/" + key + "]";
        }

        @Override
        public String getFilename() {
            return key;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public long lastModified() {
            return lastModified == null ? 0 : lastModified.toEpochMilli();
        }

        @Override
        public InputStream getInputStream() {
            return new RangedObjectStream(key);
        }
    }

    /**
     * Input stream over an S3 object that fetches lazily: {@link #skip} only moves the start offset, and the first
     * {@code read} issues one {@code GetObject} with {@code Range: bytes=<offset>-}. Closing it aborts the download,
     * so a client that stops watching does not make the server pull the rest of the file.
     */
    private final class RangedObjectStream extends InputStream {

        private final String key;
        private long offset;
        private InputStream body;

        RangedObjectStream(String key) {
            this.key = key;
        }

        private InputStream body() {
            if (body == null) {
                GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
                if (offset > 0) {
                    request.range("bytes=" + offset + "-");
                }
                body = s3.getObject(request.build());
            }
            return body;
        }

        @Override
        public long skip(long n) {
            if (body != null) {
                try {
                    return body.skip(n);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            long skipped = Math.max(0, n);
            offset += skipped; // no bytes are transferred: the offset becomes the Range start of the first read
            return skipped;
        }

        @Override
        public int read() throws IOException {
            return body().read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return body().read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }
}
