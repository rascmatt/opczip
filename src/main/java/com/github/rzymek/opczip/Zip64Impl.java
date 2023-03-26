package com.github.rzymek.opczip;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;

import static java.nio.charset.StandardCharsets.US_ASCII;

class Zip64Impl {
    private static final long PK0102 = 0x02014b50L;
    private static final long PK0304 = 0x04034b50L;
    private static final long PK0506 = 0x06054b50L;
    private static final long PK0606 = 0x06064b50L;
    private static final long PK0607 = 0x07064b50L;
    private static final long PK0708 = 0x08074b50L;

    private static final int VERSION_20 = 20;
    private static final int VERSION_45 = 45;
    private static final int DATA_DESCRIPTOR_USED = 0x08;
    private static final int ZIP64_FIELD = 0x0001;
    private static final int MAX16 = 0xffff;
    private static final long MAX32 = 0xffffffffL;

    private final OutputStream out;
    private int written = 0;

    static class Entry {
        final String filename;
        long crc;
        long size;
        long compressedSize;
        long offset;

        Entry(String filename) {
            this.filename = filename;
        }
    }

    Zip64Impl(OutputStream out) {
        this.out = out;
    }

    /**
     * Write Local File Header
     */
    int writeLFH(Entry entry) throws IOException {
        written = 0;
        writeInt(PK0304);                        // "PK\003\004"
        writeShort(VERSION_45);                  // version required: 4.5
        writeShort(DATA_DESCRIPTOR_USED);        // flags: 8 = data descriptor used
        writeShort(ZipEntry.DEFLATED);           // compression method: 8 = deflate
        writeInt(0);                          // file modification time & date
        writeInt(entry.crc);                     // CRC-32
        writeInt(MAX32);                         // compressed file size
        writeInt(MAX32);                         // uncompressed file size
        writeShort(entry.filename.length());     // filename length
        writeShort(0x20);                     // extra flags size
        byte[] filenameBytes = entry.filename.getBytes(US_ASCII);
        out.write(filenameBytes);                // filename characters

        // Extra field:
        writeShort(ZIP64_FIELD);                 // ZIP64 extra field signature
        writeShort(0x1C);                     // Size of extra field (below)
        writeLong(0);                         // Uncompressed size
        writeLong(0);                         // Compressed size
        writeLong(0);                         // Offset
        writeInt(0);                          // Number of disk on which this entry starts

        return written + filenameBytes.length;
    }

    /**
     * Write Data Descriptor
     */
    int writeDAT(Entry entry) throws IOException {
        written = 0;
        writeInt(PK0708);                        // data descriptor signature "PK\007\008"
        writeInt(entry.crc);                     // crc-32
        writeLong(entry.compressedSize);         // compressed size (zip64)
        writeLong(entry.size);                   // uncompressed size (zip64)
        return written;
    }

    /**
     * Write Central directory file header
     */
    int writeCEN(Entry entry) throws IOException {
        written = 0;
        boolean useZip64 = entry.size > MAX32;
        writeInt(PK0102);                              // "PK\001\002"
        writeShort(VERSION_45);                        // version made by: 4.5
        // TODO: LFH always requires 4.5. -> Possible mismatch
        writeShort(useZip64 ? VERSION_45 : VERSION_20);// version required: 4.5
        writeShort(DATA_DESCRIPTOR_USED);              // flags: 8 = data descriptor used
        writeShort(ZipEntry.DEFLATED);                 // compression method: 8 = deflate
        writeInt(0);                                // file modification time & date
        writeInt(entry.crc);                           // CRC-32
        writeInt(useZip64 ? MAX32 : entry.compressedSize);  // compressed size
        writeInt(useZip64 ? MAX32 : entry.size);       // uncompressed size
        writeShort(entry.filename.length());           // filename length
        writeShort(useZip64 ? 0x20 : 0);               // extra field length
        writeShort(0);                              // comment length
        writeShort(0);                              // disk number where file starts
        writeShort(0);                              // internal file attributes (unused)
        writeInt(0);                                // external file attributes (unused)
        writeInt(useZip64 ? MAX32 : entry.offset);     // LFH offset
        byte[] filenameBytes = entry.filename.getBytes(US_ASCII);
        out.write(filenameBytes);                      // filename characters

        if (useZip64)
        {
            // Extra field:
            writeShort(ZIP64_FIELD);                       // ZIP64 extra field signature
            writeShort(0x1C);                           // Size of extra field (below)
            writeLong(entry.size);                         // Uncompressed size
            writeLong(entry.compressedSize);               // Compressed size
            writeLong(entry.offset);                       // Offset
            writeInt(0);                                // Number of disk on which this entry starts
        }

        return written + filenameBytes.length;
    }

    int writeZIP64_CEN_END(long entriesCount, long cenOffset,  long cenLength) throws IOException {
        written = 0;

        writeInt(PK0606);           // Zip64 end of central dir signature
        writeLong(44);           // Size of zip64 end of central directory record (without leading 12 bytes)

        writeShort(VERSION_45);     // Version made by
        writeShort(VERSION_45);     // Version needed to extract

        writeInt(0);             // Number of this disk
        writeInt(0);             // Number of the disk with the start of the central directory

        writeLong(entriesCount);    // Total number of entries in the central directory on this disk
        writeLong(entriesCount);    // Total number of entries in the central directory
        writeLong(cenLength);       // Size of the central directory
        writeLong(cenOffset);       // Offset of start of central directory with respect to the starting disk number

        // Zip64 extensible data sector (variable length, here 0 bytes written)

        return written;
    }

    int writeZIP64_CEN_LOC(long z64CenEndOffset) throws IOException {
        written = 0;

        writeInt(PK0607);           // Zip64 end of central dir locator signature
        writeInt(0);             // Number of the disk with the start of the zip64 end of central directory
        writeLong(z64CenEndOffset); // Relative offset of the zip64 end of central directory record
        writeInt(1);             // Total number off disks

        return written;
    }

    /**
     * Write End of central directory record (EOCD)
     */
    int writeEND(long entriesCount, long cenOffset, long cenLength) throws IOException {
        written = 0;
        writeInt(PK0506);                                  // "PK\005\006"
        writeShort(0);                                  // number of this disk
        writeShort(0);                                  // central directory start disk
        writeShort((int) (Math.min(entriesCount, MAX16))); // number of directory entries on disk
        writeShort((int) (Math.min(entriesCount, MAX16))); // total number of directory entries
        writeInt((int) (Math.min(cenLength, MAX32)));      // length of central directory
        writeInt((int) (Math.min(cenOffset, MAX32)));      // offset of central directory
        writeShort(0);                                  // comment length
        return written;
    }

    /**
     * Writes a 16-bit short to the output stream in little-endian byte order.
     */
    private void writeShort(int v) throws IOException {
        OutputStream out = this.out;
        out.write((v >>> 0) & 0xff);
        out.write((v >>> 8) & 0xff);
        written += 2;
    }

    /**
     * Writes a 32-bit int to the output stream in little-endian byte order.
     */
    private void writeInt(long v) throws IOException {
        OutputStream out = this.out;
        out.write((int) ((v >>> 0) & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 24) & 0xff));
        written += 4;
    }

    /**
     * Writes a 64-bit int to the output stream in little-endian byte order.
     */
    private void writeLong(long v) throws IOException {
        OutputStream out = this.out;
        out.write((int) ((v >>> 0) & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 24) & 0xff));
        out.write((int) ((v >>> 32) & 0xff));
        out.write((int) ((v >>> 40) & 0xff));
        out.write((int) ((v >>> 48) & 0xff));
        out.write((int) ((v >>> 56) & 0xff));
        written += 8;
    }

}
