package ru.taska.controller;

/** RFC single byte range. Resolve only after authorization, so 416 does not leak file size. */
record AttachmentByteRange(long start, long end, boolean partial) {
  long length() {
    return end - start + 1;
  }

  static AttachmentByteRange parse(String header, long size) {
    if (size < 1) throw new IllegalArgumentException("Invalid object size");
    if (header == null) return new AttachmentByteRange(0, size - 1, false);
    if (header.length() > 128 || !header.matches("bytes=[0-9]*-[0-9]*"))
      throw new IllegalArgumentException("Invalid Range");
    String[] parts = header.substring(6).split("-", -1);
    try {
      if (parts[0].isEmpty()) {
        long suffix = Long.parseLong(parts[1]);
        if (suffix < 1) throw new IllegalArgumentException();
        return new AttachmentByteRange(size - Math.min(size, suffix), size - 1, true);
      }
      long start = Long.parseLong(parts[0]);
      long end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
      if (start >= size || end < start) throw new IllegalArgumentException();
      return new AttachmentByteRange(start, end, true);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid Range");
    }
  }
}
