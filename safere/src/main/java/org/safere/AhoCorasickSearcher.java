// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * A fast multi-string literal search engine using the Aho-Corasick algorithm compiled into a
 * flattened direct DFA transition table with streaming SIMD root-state prefiltering.
 *
 * <p>Designed for allocation-free, single-indexed O(1) state transitions and multi-gigabyte/sec
 * scanning across large keyword dictionaries (K > 128 up to 10,000+ keywords).
 */
final class AhoCorasickSearcher implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final class BuilderNode {
    final Map<Integer, BuilderNode> children = new HashMap<>();
    BuilderNode fail;
    int matchIndex = -1;
  }

  // Pre-computed DFA transition table: [numStates * 128]
  private final char[] transitions;
  private final int[] failureLinks;
  private final int[] matchIndices;
  private final int[] patternLengths;
  private final int maxPatternLength;
  private final boolean caseInsensitive;
  private final boolean isAsciiOnly;
  private final int[] rootRanges;
  private final HashMap<Long, Integer> nonAsciiTransitions;

  static AhoCorasickSearcher create(String[] patterns, boolean caseInsensitive) {
    if (patterns == null || patterns.length < 2 || !VectorScanProviders.teddyProviderAvailable()) {
      return null;
    }
    return new AhoCorasickSearcher(Arrays.asList(patterns), caseInsensitive);
  }

  static AhoCorasickSearcher create(List<String> patterns, boolean caseInsensitive) {
    if (patterns == null || patterns.size() < 2 || !VectorScanProviders.teddyProviderAvailable()) {
      return null;
    }
    return new AhoCorasickSearcher(patterns, caseInsensitive);
  }

  AhoCorasickSearcher(List<String> patterns, boolean caseInsensitive) {
    this.caseInsensitive = caseInsensitive;
    this.patternLengths = new int[patterns.size()];
    int maxLen = 0;
    boolean allAscii = true;
    for (int i = 0; i < patterns.size(); i++) {
      String p = patterns.get(i);
      byte[] utf8 = p.getBytes(StandardCharsets.UTF_8);
      int length = utf8.length;
      this.patternLengths[i] = length;
      maxLen = Math.max(maxLen, length);
      if (allAscii && length != p.length()) {
        allAscii = false;
      }
    }
    this.maxPatternLength = maxLen;
    this.isAsciiOnly = allAscii;

    BuilderNode root = new BuilderNode();

    // 1. Insert patterns into Trie
    for (int i = 0; i < patterns.size(); i++) {
      String pattern = patterns.get(i);
      byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
      BuilderNode curr = root;
      for (byte b : bytes) {
        int u = b & 0xFF;
        if (caseInsensitive && u < 128) {
          u = Ascii.toLowerCase((char) u);
        }
        curr = curr.children.computeIfAbsent(u, k -> new BuilderNode());
      }
      if (curr.matchIndex == -1) {
        curr.matchIndex = i;
      }
    }

    // 2. Compute failure links via BFS
    Queue<BuilderNode> queue = new ArrayDeque<>();
    root.fail = root;
    for (BuilderNode child : root.children.values()) {
      child.fail = root;
      queue.add(child);
    }

    while (!queue.isEmpty()) {
      BuilderNode curr = queue.poll();
      for (Map.Entry<Integer, BuilderNode> entry : curr.children.entrySet()) {
        int c = entry.getKey();
        BuilderNode child = entry.getValue();
        BuilderNode f = curr.fail;
        while (f != root && !f.children.containsKey(c)) {
          f = f.fail;
        }
        child.fail = f.children.getOrDefault(c, root);
        if (child.matchIndex == -1) {
          child.matchIndex = child.fail.matchIndex;
        }
        queue.add(child);
      }
    }

    // 3. Assign sequential state IDs in BFS order (root is state 0)
    List<BuilderNode> nodeList = new ArrayList<>();
    Queue<BuilderNode> compileQueue = new ArrayDeque<>();
    nodeList.add(root);
    compileQueue.add(root);
    Map<BuilderNode, Integer> nodeIndices = new HashMap<>();
    nodeIndices.put(root, 0);

    while (!compileQueue.isEmpty()) {
      BuilderNode curr = compileQueue.poll();
      for (BuilderNode child : curr.children.values()) {
        nodeIndices.put(child, nodeList.size());
        nodeList.add(child);
        compileQueue.add(child);
      }
    }

    int numNodes = nodeList.size();
    this.transitions = new char[numNodes * 128];
    this.failureLinks = new int[numNodes];
    this.matchIndices = new int[numNodes];
    this.nonAsciiTransitions = allAscii ? null : new HashMap<>();

    // 4. Precompute direct DFA transitions in BFS order
    AsciiBitmap.Builder rootBitmapBuilder = new AsciiBitmap.Builder();
    for (int state = 0; state < numNodes; state++) {
      BuilderNode node = nodeList.get(state);
      this.failureLinks[state] = nodeIndices.get(node.fail);
      this.matchIndices[state] = node.matchIndex;
      int baseOffset = state << 7;

      for (int c = 0; c < 128; c++) {
        BuilderNode child = node.children.get(c);
        if (child != null) {
          int nextState = nodeIndices.get(child);
          transitions[baseOffset | c] = (char) nextState;
          if (state == 0 && nextState != 0) {
            rootBitmapBuilder.add((char) c);
            if (caseInsensitive) {
              rootBitmapBuilder.add(Ascii.toUpperCase((char) c));
            }
          }
        } else if (state == 0) {
          transitions[baseOffset | c] = 0;
        } else {
          int failState = nodeIndices.get(node.fail);
          // failState < state by BFS invariant, so failState transitions are already computed
          transitions[baseOffset | c] = transitions[(failState << 7) | c];
        }
      }

      if (!allAscii) {
        for (Map.Entry<Integer, BuilderNode> entry : node.children.entrySet()) {
          int c = entry.getKey();
          if (c >= 128) {
            nonAsciiTransitions.put(((long) state << 32) | c, nodeIndices.get(entry.getValue()));
          }
        }
      }
    }

    AsciiBitmap rootBitmap = rootBitmapBuilder.build();
    this.rootRanges = rootBitmap.isEmpty() ? null : rootBitmap.toRanges();
  }

  /**
   * Scans the UTF-8 byte array starting from the given offset, utilizing SIMD vector prefiltering
   * on root-state transitions. Returns the earliest start index of any matched literal pattern, or
   * -1 if no matches are found.
   */
  int findNext(byte[] bytes, int offset, int length, int start) {
    int state = 0;
    int pos = Math.max(0, start);
    int bestStart = -1;

    if (isAsciiOnly && !caseInsensitive) {
      while (pos < length) {
        // SIMD vector root prefilter when in root state
        if (state == 0 && rootRanges != null && (length - pos) >= 64) {
          VectorScanProvider provider = VectorScanProviders.providerForLength(length);
          if (provider != null) {
            int rootMatch = provider.indexOfAsciiClass(bytes, offset, length, rootRanges, pos);
            if (rootMatch < 0) {
              return bestStart;
            }
            if (bestStart >= 0 && rootMatch >= bestStart + maxPatternLength - 1) {
              return bestStart;
            }
            pos = rootMatch;
          }
        }

        int b = bytes[offset + pos] & 0xFF;
        state = (b < 128) ? transitions[(state << 7) | b] : 0;
        int patternIdx = matchIndices[state];
        if (patternIdx != -1) {
          int patternLen = patternLengths[patternIdx];
          int matchStart = pos - patternLen + 1;
          if (bestStart < 0 || matchStart < bestStart) {
            bestStart = matchStart;
          }
        }
        if (canReturnBestStart(bestStart, pos, start)) {
          return bestStart;
        }
        pos++;
      }
    } else {
      while (pos < length) {
        if (state == 0 && rootRanges != null && (length - pos) >= 64) {
          VectorScanProvider provider = VectorScanProviders.providerForLength(length);
          if (provider != null) {
            int rootMatch = provider.indexOfAsciiClass(bytes, offset, length, rootRanges, pos);
            if (rootMatch < 0) {
              return bestStart;
            }
            if (bestStart >= 0 && rootMatch >= bestStart + maxPatternLength - 1) {
              return bestStart;
            }
            pos = rootMatch;
          }
        }

        int b = bytes[offset + pos] & 0xFF;
        if (caseInsensitive && b < 128) {
          b = Ascii.toLowerCase((char) b);
        }
        if (b < 128) {
          state = transitions[(state << 7) | b];
        } else {
          Integer next =
              nonAsciiTransitions != null
                  ? nonAsciiTransitions.get(((long) state << 32) | b)
                  : null;
          if (next != null) {
            state = next;
          } else {
            int f = failureLinks[state];
            while (f != 0 && (next = nonAsciiTransitions.get(((long) f << 32) | b)) == null) {
              f = failureLinks[f];
            }
            state =
                (next != null)
                    ? next
                    : (f == 0 ? nonAsciiTransitions.getOrDefault((long) b, 0) : 0);
          }
        }

        int patternIdx = matchIndices[state];
        if (patternIdx != -1) {
          int patternLen = patternLengths[patternIdx];
          int matchStart = pos - patternLen + 1;
          if (bestStart < 0 || matchStart < bestStart) {
            bestStart = matchStart;
          }
        }
        if (canReturnBestStart(bestStart, pos, start)) {
          return bestStart;
        }
        pos++;
      }
    }
    return bestStart;
  }

  private boolean canReturnBestStart(int bestStart, int currentIndex, int start) {
    return bestStart >= 0
        && (bestStart == start || currentIndex >= bestStart + maxPatternLength - 1);
  }
}
