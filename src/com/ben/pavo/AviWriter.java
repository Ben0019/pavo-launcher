package com.ben.pavo;

import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Small AVI/RIFF writer used for the AVI recording option.  Video is H.264 in
 * Annex-B form and audio is uncompressed 16-bit PCM, so it does not need the
 * platform MediaMuxer (which has no AVI output).
 */
final class AviWriter {
    private static final int AVIF_HASINDEX = 0x00000010;

    private final RandomAccessFile file;
    private final int width;
    private final int height;
    private final int fps;
    private final boolean audio;
    private final int sampleRate;
    private final int channels;
    private final int blockAlign;
    private final long avihOffset;
    private final long videoStrhOffset;
    private final long moviSizeOffset;
    private final long moviListStart;
    private final long moviDataStart;
    private final List<IndexEntry> index = new ArrayList<>();
    private byte[] codecConfig;
    private long videoFrames;
    private long audioFrames;
    private long lastVideoPtsUs;
    private boolean finished;
    private boolean released;
    private boolean firstVideo = true;

    AviWriter(File file, int width, int height, int fps, boolean audio,
            int sampleRate, int channels) throws IOException {
        this.file = new RandomAccessFile(file, "rw");
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.audio = audio;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.blockAlign = channels * 2;
        this.file.setLength(0);

        writeFourCC("RIFF");
        writeInt(0); // file size, patched in finish()
        writeFourCC("AVI ");

        writeFourCC("LIST");
        writeInt(hdrlSize());
        writeFourCC("hdrl");

        writeFourCC("avih");
        writeInt(56);
        avihOffset = this.file.getFilePointer();
        writeAvih();

        // Video stream list.
        writeFourCC("LIST");
        writeInt(4 + (8 + 56) + (8 + 40));
        writeFourCC("strl");
        writeFourCC("strh");
        writeInt(56);
        videoStrhOffset = this.file.getFilePointer();
        writeVideoStrh();
        writeFourCC("strf");
        writeInt(40);
        writeBitmapInfo();

        if (audio) {
            writeFourCC("LIST");
            // 'auds' + strh + strf(WAVEFORMATEX)
            writeInt(4 + (8 + 56) + (8 + 18));
            writeFourCC("strl");
            writeFourCC("strh");
            writeInt(56);
            audioStrhOffsetValue = this.file.getFilePointer();
            writeAudioStrh();
            writeFourCC("strf");
            writeInt(18);
            writeWaveFormat();
        }

        writeFourCC("LIST");
        moviSizeOffset = this.file.getFilePointer();
        writeInt(0); // patched in finish()
        moviListStart = this.file.getFilePointer();
        writeFourCC("movi");
        moviDataStart = this.file.getFilePointer();
    }

    private long audioStrhOffsetValue = -1L;

    private int hdrlSize() {
        int size = 4; // 'hdrl'
        size += 8 + 56; // avih
        size += 4 + (8 + 56) + (8 + 40); // video strl
        if (audio) size += 4 + (8 + 56) + (8 + 18);
        return size;
    }

    private void writeAvih() throws IOException {
        writeInt(1_000_000 / fps);       // dwMicroSecPerFrame
        writeInt(Math.max(1, 4000000 / 8)); // dwMaxBytesPerSec placeholder
        writeInt(0);                     // dwPaddingGranularity
        writeInt(AVIF_HASINDEX);         // dwFlags
        writeInt(0);                     // dwTotalFrames, patched
        writeInt(0);                     // dwInitialFrames
        writeInt(audio ? 2 : 1);         // dwStreams
        writeInt(0);                     // dwSuggestedBufferSize
        writeInt(width);
        writeInt(height);
        for (int i = 0; i < 4; i++) writeInt(0);
    }

    private void writeVideoStrh() throws IOException {
        writeFourCC("vids");
        writeFourCC("H264");
        writeInt(0);      // flags
        writeShort(0);    // priority
        writeShort(0);    // language
        writeInt(0);      // initial frames
        writeInt(1);      // scale
        writeInt(fps);    // rate
        writeInt(0);      // start
        writeInt(0);      // length, patched
        writeInt(0);      // suggested buffer
        writeInt(-1);     // quality
        writeInt(0);      // sample size
        writeShort(0);
        writeShort(0);
        writeShort(Math.min(65535, width));
        writeShort(Math.min(65535, height));
    }

    private void writeBitmapInfo() throws IOException {
        writeInt(40);             // biSize
        writeInt(width);          // biWidth
        writeInt(height);         // biHeight
        writeShort(1);            // biPlanes
        writeShort(24);           // biBitCount
        writeFourCC("H264");      // biCompression
        writeInt(0);              // biSizeImage
        writeInt(0);              // biXPelsPerMeter
        writeInt(0);              // biYPelsPerMeter
        writeInt(0);              // biClrUsed
        writeInt(0);              // biClrImportant
    }

    private void writeAudioStrh() throws IOException {
        writeFourCC("auds");
        writeInt(0);              // handler
        writeInt(0);              // flags
        writeShort(0);            // priority
        writeShort(0);            // language
        writeInt(0);              // initial frames
        writeInt(blockAlign);     // scale
        writeInt(sampleRate * blockAlign); // rate
        writeInt(0);              // start
        writeInt(0);              // length, patched
        writeInt(0);              // suggested buffer
        writeInt(-1);             // quality
        writeInt(blockAlign);     // sample size
        writeShort(0);
        writeShort(0);
        writeShort(0);
        writeShort(0);
    }

    private void writeWaveFormat() throws IOException {
        writeShort(1);                       // WAVE_FORMAT_PCM
        writeShort(channels);
        writeInt(sampleRate);
        writeInt(sampleRate * blockAlign);   // avg bytes/sec
        writeShort(blockAlign);
        writeShort(16);                      // bits per sample
        writeShort(0);                       // cbSize
    }

    /** Captures the H.264 codec configuration so an AVI keyframe is self-contained. */
    synchronized void setCodecConfig(MediaFormat format) {
        if (format == null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appendCsd(out, format, "csd-0");
        appendCsd(out, format, "csd-1");
        if (out.size() > 0) codecConfig = out.toByteArray();
    }

    private static void appendCsd(ByteArrayOutputStream out, MediaFormat format, String key) {
        try {
            ByteBuffer b = format.getByteBuffer(key);
            if (b == null) return;
            b.rewind();
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            out.write(bytes, 0, bytes.length);
        } catch (Throwable ignored) {}
    }

    synchronized void writeVideo(ByteBuffer buffer, long ptsUs, boolean keyframe)
            throws IOException {
        if (finished || buffer == null) return;
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        data = toAnnexB(data);
        if (firstVideo && codecConfig != null && !containsSps(data)) {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(codecConfig, 0, codecConfig.length);
            joined.write(data, 0, data.length);
            data = joined.toByteArray();
        }
        firstVideo = false;
        lastVideoPtsUs = Math.max(lastVideoPtsUs, ptsUs);

        long chunkStart = file.getFilePointer();
        writeFourCC("00dc");
        writeInt(data.length);
        file.write(data);
        if ((data.length & 1) != 0) file.write(0);
        index.add(new IndexEntry(chunkStart - moviListStart, data.length,
                keyframe ? 0x10 : 0, false));
        videoFrames++;
    }

    synchronized void writePcm(short[] samples, int frames) throws IOException {
        if (finished || !audio || frames <= 0) return;
        int bytes = frames * channels * 2;
        byte[] pcm = new byte[bytes];
        int offset = 0;
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                short value = samples[i * channels + c];
                pcm[offset++] = (byte) (value & 0xFF);
                pcm[offset++] = (byte) ((value >> 8) & 0xFF);
            }
        }
        long chunkStart = file.getFilePointer();
        writeFourCC("01wb");
        writeInt(pcm.length);
        file.write(pcm);
        if ((pcm.length & 1) != 0) file.write(0);
        index.add(new IndexEntry(chunkStart - moviListStart, pcm.length, 0, true));
        audioFrames += frames;
    }

    synchronized void finish() throws IOException {
        if (finished) return;
        finished = true;
        long moviEnd = file.getFilePointer();
        file.seek(moviEnd);
        writeFourCC("idx1");
        writeInt(index.size() * 16);
        for (IndexEntry entry : index) {
            writeFourCC(entry.audio ? "01wb" : "00dc");
            writeInt(entry.flags);
            writeInt((int) entry.offset);
            writeInt(entry.size);
        }
        long fileEnd = file.getFilePointer();

        file.seek(4);
        writeInt((int) Math.min(Integer.MAX_VALUE, fileEnd - 8));
        file.seek(moviSizeOffset);
        writeInt((int) Math.min(Integer.MAX_VALUE,
                moviEnd - moviListStart));
        file.seek(avihOffset + 16);
        writeInt((int) Math.min(Integer.MAX_VALUE, videoFrames));
        file.seek(videoStrhOffset + 32);
        writeInt((int) Math.min(Integer.MAX_VALUE, videoFrames));
        if (audio && audioStrhOffsetValue >= 0) {
            file.seek(audioStrhOffsetValue + 32);
            writeInt((int) Math.min(Integer.MAX_VALUE, audioFrames));
        }
        // A screen that does not change produces very few encoded frames.
        // Declare AVI's fixed frame rate from the real audio/video duration so
        // the file does not end at the last changed frame while audio continues.
        long durationUs = audio && audioFrames > 0
                ? audioFrames * 1_000_000L / Math.max(1, sampleRate)
                : lastVideoPtsUs;
        if (videoFrames > 0 && durationUs > 0) {
            long rate = Math.max(1L, videoFrames * 1_000_000_000L
                    / Math.max(1L, durationUs));
            int micros = (int) Math.max(1L, durationUs / videoFrames);
            file.seek(avihOffset);
            writeInt(micros);
            file.seek(videoStrhOffset + 20);
            writeInt(1000);
            writeInt((int) Math.min(Integer.MAX_VALUE, rate));
        }
        file.seek(fileEnd);
        file.getFD().sync();
    }

    synchronized void release() {
        if (released) return;
        released = true;
        try { file.close(); } catch (Throwable ignored) {}
    }

    private static byte[] toAnnexB(byte[] data) {
        if (data == null || data.length < 4) return data;
        if (isStartCode(data, 0)) return data;
        // Some encoders return AVCC length-prefixed NAL units. Convert those
        // to the Annex-B framing that AVI H.264 streams normally use.
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        int p = 0;
        while (p + 4 <= data.length) {
            int size = ((data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16)
                    | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
            p += 4;
            if (size <= 0 || p + size > data.length) return data;
            out.write(0); out.write(0); out.write(0); out.write(1);
            out.write(data, p, size);
            p += size;
        }
        return p == data.length ? out.toByteArray() : data;
    }

    private static boolean containsSps(byte[] data) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (isStartCode(data, i)) {
                int header = i + 3;
                if (header < data.length && data[header] == 0) header++;
                if (header < data.length && (data[header] & 0x1F) == 7) return true;
            }
        }
        return false;
    }

    private static boolean isStartCode(byte[] data, int offset) {
        return offset + 3 < data.length
                && data[offset] == 0 && data[offset + 1] == 0
                && (data[offset + 2] == 1
                || (data[offset + 2] == 0 && offset + 3 < data.length
                && data[offset + 3] == 1));
    }

    private void writeFourCC(String value) throws IOException {
        for (int i = 0; i < 4; i++) {
            file.write(i < value.length() ? value.charAt(i) : ' ');
        }
    }

    private void writeInt(int value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >>> 8) & 0xFF);
        file.write((value >>> 16) & 0xFF);
        file.write((value >>> 24) & 0xFF);
    }

    private void writeShort(int value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >>> 8) & 0xFF);
    }

    private static final class IndexEntry {
        final long offset;
        final int size;
        final int flags;
        final boolean audio;

        IndexEntry(long offset, int size, int flags, boolean audio) {
            this.offset = offset;
            this.size = size;
            this.flags = flags;
            this.audio = audio;
        }
    }
}
