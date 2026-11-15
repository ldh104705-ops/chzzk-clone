package com.chzzk.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Stores videos as files under {@code app.storage.dir} with random UUID names. */
@Component
public class LocalVideoStorage implements VideoStorage {

    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");
    private static final Pattern STORED_NAME = Pattern.compile("[0-9a-f-]{36}(\\.[a-z0-9]{1,8})?");

    private final Path root;

    public LocalVideoStorage(@Value("${app.storage.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("저장 디렉터리를 만들 수 없습니다: " + root, e);
        }
    }

    @Override
    public StoredVideo store(InputStream content, String originalName, String contentType) {
        // The stored name is random; only a sanitized extension is taken from the client's file name.
        String storedName = UUID.randomUUID() + extensionOf(originalName);
        Path target = resolve(storedName);
        try {
            long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredVideo(storedName, originalName, contentType, size);
        } catch (IOException e) {
            throw new UncheckedIOException("영상을 저장하지 못했습니다.", e);
        }
    }

    @Override
    public Resource load(String storedName) {
        Path path = resolve(storedName);
        if (!Files.isRegularFile(path)) {
            throw new VideoNotFoundException("저장된 영상 파일을 찾을 수 없습니다.");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storedName) {
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Resolves a stored name inside the root, rejecting anything that could escape it. */
    private Path resolve(String storedName) {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            throw new VideoNotFoundException("잘못된 파일 이름입니다.");
        }
        Path path = root.resolve(storedName).normalize();
        if (!path.startsWith(root)) {
            throw new VideoNotFoundException("잘못된 파일 경로입니다.");
        }
        return path;
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
}
