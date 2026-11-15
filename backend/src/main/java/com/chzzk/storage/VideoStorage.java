package com.chzzk.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Where uploaded video bytes live. The local-disk implementation is used now; an S3/R2
 * implementation can replace it later without touching the broadcast code.
 */
public interface VideoStorage {

    /** Stores the stream under a freshly generated name and returns that name. */
    StoredVideo store(InputStream content, String originalName, String contentType);

    /** Loads a previously stored video. Throws {@link VideoNotFoundException} if missing. */
    Resource load(String storedName);

    /** Deletes a stored video; missing files are ignored. */
    void delete(String storedName);
}
