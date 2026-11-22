package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

/** Prepared without sound; one explicit start with a one-way stop/completion boundary. */
interface SpeakerAudioPlayback extends AutoCloseable {
    void start();
    boolean isRunning();
    @Override void close();
}
