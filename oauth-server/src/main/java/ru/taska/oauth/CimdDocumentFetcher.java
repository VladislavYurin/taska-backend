package ru.taska.oauth;

import java.net.URI;
import java.time.Duration;

interface CimdDocumentFetcher {
  record Document(byte[] json, Duration cacheFor) {}

  Document fetch(URI uri);
}
