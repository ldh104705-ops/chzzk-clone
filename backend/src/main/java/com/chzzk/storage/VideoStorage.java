package com.chzzk.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Where uploaded video bytes live: local disk ({@link LocalVideoStorage}, default) or an S3-compatible bucket
 * ({@link S3VideoStorage}: MinIO locally, Cloudflare R2 in production). Several backend instances need the S3 one,
 * because a file on one instance's disk is invisible to the others.
 */
public interface VideoStorage {

    /** Stores the stream under a freshly generated name and returns that name. */
    StoredVideo store(InputStream content, String originalName, String contentType);

    /** Loads a previously stored video. Throws {@link VideoNotFoundException} if missing. */
    Resource load(String storedName);

    /** Deletes a stored video; missing files are ignored. */
    void delete(String storedName);
}
