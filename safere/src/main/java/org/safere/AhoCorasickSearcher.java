// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * A fast multi-string literal search engine using the Aho-Corasick algorithm. Designed to compile
 * transitions into flat primitive arrays for allocation-free matching.
 */
public final class AhoCorasickSearcher {

  private static final class BuilderNode {
    final Map<Character, BuilderNode> children = new HashMap<>();
    BuilderNode fail;
    int matchIndex = -1;
  }

  // Compressed transition table representation
  private final int[] firstChild;
  private final int[] numChildren;
  private final char[] transitionChars;
  private final int[] transitionStates;
  private final int[] failureLinks;
  private final int[] matchIndices;
  private final int[] patternLengths;
  private final int maxPatternLength;
  private final boolean caseInsensitive;

  public AhoCorasickSearcher(List<String> patterns, boolean caseInsensitive) {
    this.caseInsensitive = caseInsensitive;
    this.patternLengths = new int[patterns.size()];
    int maxLen = 0;
    for (int i = 0; i < patterns.size(); i++) {
      int length = patterns.get(i).length();
      this.patternLengths[i] = length;
      maxLen = Math.max(maxLen, length);
    }
    this.maxPatternLength = maxLen;

    BuilderNode root = new BuilderNode();

    // 1. Insert patterns into Trie
    for (int i = 0; i < patterns.size(); i++) {
      String pattern = patterns.get(i);
      BuilderNode curr = root;
      for (int j = 0; j < pattern.length(); j++) {
        char c = pattern.charAt(j);
        if (caseInsensitive) {
          c = Character.toLowerCase(c);
        }
        curr = curr.children.computeIfAbsent(c, k -> new BuilderNode());
      }
      curr.matchIndex = i;
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
      for (Map.Entry<Character, BuilderNode> entry : curr.children.entrySet()) {
        char c = entry.getKey();
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

    // 3. Compile the builder nodes into flat arrays
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
    this.firstChild = new int[numNodes];
    this.numChildren = new int[numNodes];
    this.failureLinks = new int[numNodes];
    this.matchIndices = new int[numNodes];

    List<Character> charList = new ArrayList<>();
    List<Integer> stateList = new ArrayList<>();

    for (int i = 0; i < numNodes; i++) {
      BuilderNode node = nodeList.get(i);
      this.failureLinks[i] = nodeIndices.get(node.fail);
      this.matchIndices[i] = node.matchIndex;
      this.firstChild[i] = charList.size();
      this.numChildren[i] = node.children.size();

      for (Map.Entry<Character, BuilderNode> entry : node.children.entrySet()) {
        charList.add(entry.getKey());
        stateList.add(nodeIndices.get(entry.getValue()));
      }
    }

    this.transitionChars = new char[charList.size()];
    for (int i = 0; i < charList.size(); i++) {
      this.transitionChars[i] = charList.get(i);
    }
    this.transitionStates = new int[stateList.size()];
    for (int i = 0; i < stateList.size(); i++) {
      this.transitionStates[i] = stateList.get(i);
    }
  }

  private int nextState(int state, char c) {
    int start = firstChild[state];
    int count = numChildren[state];
    for (int i = 0; i < count; i++) {
      if (transitionChars[start + i] == c) {
        return transitionStates[start + i];
      }
    }
    return -1;
  }

  /**
   * Scans the text starting from the given offset, and returns the earliest start index of any
   * matched literal pattern. Returns -1 if no matches are found.
   */
  public int findNext(CharSequence text, int start) {
    int state = 0;
    int len = text.length();
    int bestStart = -1;
    // Split the search loop based on case-insensitivity to avoid evaluating the conditional on
    // every character iteration. This eliminates branching in the inner matching loops.
    if (caseInsensitive) {
      for (int i = start; i < len; i++) {
        char c = Character.toLowerCase(text.charAt(i));
        int next = nextState(state, c);
        while (next == -1 && state != 0) {
          state = failureLinks[state];
          next = nextState(state, c);
        }
        state = (next != -1) ? next : 0;

        if (matchIndices[state] != -1) {
          int patternIdx = matchIndices[state];
          int patternLen = patternLengths[patternIdx];
          int matchStart = i - patternLen + 1;
          if (bestStart < 0 || matchStart < bestStart) {
            bestStart = matchStart;
          }
        }
        if (canReturnBestStart(bestStart, i)) {
          return bestStart;
        }
      }
    } else {
      for (int i = start; i < len; i++) {
        char c = text.charAt(i);
        int next = nextState(state, c);
        while (next == -1 && state != 0) {
          state = failureLinks[state];
          next = nextState(state, c);
        }
        state = (next != -1) ? next : 0;

        if (matchIndices[state] != -1) {
          int patternIdx = matchIndices[state];
          int patternLen = patternLengths[patternIdx];
          int matchStart = i - patternLen + 1;
          if (bestStart < 0 || matchStart < bestStart) {
            bestStart = matchStart;
          }
        }
        if (canReturnBestStart(bestStart, i)) {
          return bestStart;
        }
      }
    }
    return bestStart;
  }

  private boolean canReturnBestStart(int bestStart, int currentIndex) {
    return bestStart >= 0 && currentIndex >= bestStart + maxPatternLength - 1;
  }
}
