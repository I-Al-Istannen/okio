/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

import static kotlin.text.Charsets.UTF_8;
import static kotlin.text.StringsKt.repeat;
import static okio.TestUtil.SEGMENT_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests solely for the behavior of RealBufferedSource's implementation. For generic
 * BufferedSource behavior use BufferedSourceTest.
 */
public final class BufferedSourceJavaTest {
  @Test public void inputStreamTracksSegments() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8("a");
    source.writeUtf8(repeat("b", SEGMENT_SIZE));
    source.writeUtf8("c");

    InputStream in = Okio.buffer((RawSource) source).inputStream();
    assertEquals(0, in.available());
    assertEquals(SEGMENT_SIZE + 2, source.size());

    // Reading one byte buffers a full segment.
    assertEquals('a', in.read());
    assertEquals(SEGMENT_SIZE - 1, in.available());
    assertEquals(2, source.size());

    // Reading as much as possible reads the rest of that buffered segment.
    byte[] data = new byte[SEGMENT_SIZE * 2];
    assertEquals(SEGMENT_SIZE - 1, in.read(data, 0, data.length));
    assertEquals(repeat("b", SEGMENT_SIZE - 1), new String(data, 0, SEGMENT_SIZE - 1, UTF_8));
    assertEquals(2, source.size());

    // Continuing to read buffers the next segment.
    assertEquals('b', in.read());
    assertEquals(1, in.available());
    assertEquals(0, source.size());

    // Continuing to read reads from the buffer.
    assertEquals('c', in.read());
    assertEquals(0, in.available());
    assertEquals(0, source.size());

    // Once we've exhausted the source, we're done.
    assertEquals(-1, in.read());
    assertEquals(0, source.size());
  }

  @Test public void inputStreamCloses() throws Exception {
    BufferedSource source = Okio.buffer((RawSource) new Buffer());
    InputStream in = source.inputStream();
    in.close();
    try {
      source.require(1);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void indexOfStopsReadingAtLimit() throws Exception {
    Buffer buffer = new Buffer().writeUtf8("abcdef");
    BufferedSource bufferedSource = Okio.buffer(new ForwardingSource(buffer) {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        return super.read(sink, Math.min(1, byteCount));
      }
    });

    assertEquals(6, buffer.size());
    assertEquals(-1, bufferedSource.indexOf((byte) 'e', 0, 4));
    assertEquals(2, buffer.size());
  }

  @Test public void requireTracksBufferFirst() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    bufferedSource.getBuffer().writeUtf8("aa");

    bufferedSource.require(2);
    assertEquals(2, bufferedSource.getBuffer().size());
    assertEquals(2, source.size());
  }

  @Test public void requireIncludesBufferBytes() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8("b");

    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    bufferedSource.getBuffer().writeUtf8("a");

    bufferedSource.require(2);
    assertEquals("ab", bufferedSource.getBuffer().readUtf8(2));
  }

  @Test public void requireInsufficientData() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8("a");

    BufferedSource bufferedSource = Okio.buffer((RawSource) source);

    try {
      bufferedSource.require(2);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void requireReadsOneSegmentAtATime() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8(repeat("a", SEGMENT_SIZE));
    source.writeUtf8(repeat("b", SEGMENT_SIZE));

    BufferedSource bufferedSource = Okio.buffer((RawSource) source);

    bufferedSource.require(2);
    assertEquals(SEGMENT_SIZE, source.size());
    assertEquals(SEGMENT_SIZE, bufferedSource.getBuffer().size());
  }

  @Test public void skipReadsOneSegmentAtATime() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8(repeat("a", SEGMENT_SIZE));
    source.writeUtf8(repeat("b", SEGMENT_SIZE));
    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    bufferedSource.skip(2);
    assertEquals(SEGMENT_SIZE, source.size());
    assertEquals(SEGMENT_SIZE - 2, bufferedSource.getBuffer().size());
  }

  @Test public void skipTracksBufferFirst() throws Exception {
    Buffer source = new Buffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    bufferedSource.getBuffer().writeUtf8("aa");

    bufferedSource.skip(2);
    assertEquals(0, bufferedSource.getBuffer().size());
    assertEquals(2, source.size());
  }

  @Test public void operationsAfterClose() throws IOException {
    Buffer source = new Buffer();
    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    bufferedSource.close();

    // Test a sample set of methods.
    try {
      bufferedSource.indexOf((byte) 1);
      fail();
    } catch (IllegalStateException expected) {
    }

    try {
      bufferedSource.skip(1);
      fail();
    } catch (IllegalStateException expected) {
    }

    try {
      bufferedSource.readByte();
      fail();
    } catch (IllegalStateException expected) {
    }

    try {
      bufferedSource.readByteString(10);
      fail();
    } catch (IllegalStateException expected) {
    }

    // Test a sample set of methods on the InputStream.
    InputStream is = bufferedSource.inputStream();
    try {
      is.read();
      fail();
    } catch (IOException expected) {
    }

    try {
      is.read(new byte[10]);
      fail();
    } catch (IOException expected) {
    }
  }

  /**
   * We don't want readAll to buffer an unbounded amount of data. Instead it
   * should buffer a segment, write it, and repeat.
   */
  @Test public void readAllReadsOneSegmentAtATime() throws IOException {
    Buffer write1 = new Buffer().writeUtf8(repeat("a", SEGMENT_SIZE));
    Buffer write2 = new Buffer().writeUtf8(repeat("b", SEGMENT_SIZE));
    Buffer write3 = new Buffer().writeUtf8(repeat("c", SEGMENT_SIZE));

    Buffer source = new Buffer().writeUtf8(""
        + repeat("a", SEGMENT_SIZE)
        + repeat("b", SEGMENT_SIZE)
        + repeat("c", SEGMENT_SIZE));

    MockSink mockSink = new MockSink();
    BufferedSource bufferedSource = Okio.buffer((RawSource) source);
    assertEquals(SEGMENT_SIZE * 3, bufferedSource.readAll(mockSink));
    mockSink.assertLog(
        "write(" + write1 + ", " + write1.size() + ")",
        "write(" + write2 + ", " + write2.size() + ")",
        "write(" + write3 + ", " + write3.size() + ")");
  }
}
