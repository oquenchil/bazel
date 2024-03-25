// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * TODO: On linux IIUC 246 different bytes are allowed in dir names. For now we will use hashes
 *  but for a checked in implementation, more lower dirs can be squeezed per overlayfs mount using
 *  those 246 characters.
 *
 *  TODO: check if --sandbox_base works (on tmpfs too)
 */
public class DirsMerkleTree {

  public static Set<String> merkleSet = Sets.newConcurrentHashSet();
  public static Map<String, Lock> merkleSetLocks = new ConcurrentHashMap<>();
  private static final String STASHED_DIR = "_stashed_dirs";


  static class Result {
    List<PathFragment> dirsToCreate = new ArrayList<>();
    List<String> lowerDirs = new ArrayList<>();

    Map<PathFragment, PathFragment>  stashedSymlinksToCreate = new HashMap<>();
  }

  static class PathStackNode {
    PathFragment path;
    List<PathFragment> files = new ArrayList<>();
    String hash;


    void computeHash() {
      Hasher hasher = Hashing.goodFastHash(32).newHasher();
      hasher.putString(path.toString(), StandardCharsets.UTF_8);
      for (PathFragment filename : files) {
        hasher.putString(filename.getBaseName(), StandardCharsets.UTF_8);
      }
      hash = hasher.hash().toString();
    }
  }
  // Implementation that exploits inputs being sorted
  public static Result buildTree(SandboxInputs sandboxInputs) {
    Map<PathFragment, PathStackNode> nodes = new HashMap<>();
    PathStackNode rootNode = new PathStackNode();
    Result result = new Result();
    try (SilentCloseable c = Profiler.instance().profile("DirsMerkelTree.buildCurrentTree")) {
      for (var entry : sandboxInputs.getFiles().entrySet()) {
        PathFragment parent = entry.getKey().getParentDirectory();
        PathStackNode parentNode = nodes.get(parent);
        if (parentNode == null) {
          if (parent.isEmpty()) {
            parentNode = rootNode;
          } else {
            parentNode = new PathStackNode();
            parentNode.path = parent;
            nodes.put(parent, parentNode);
          }
        }
        parentNode.files.add(entry.getKey());
      }
      for (var entry : nodes.entrySet()) {
        PathStackNode node = entry.getValue();
        node.computeHash();
        String nodeStashDir = getNodeStashDir(node);
        if (!merkleSet.contains(node.hash)) {
          result.dirsToCreate.add(PathFragment.create(nodeStashDir));
          for (PathFragment file : node.files) {
            result.stashedSymlinksToCreate.put(file,
                PathFragment.create(nodeStashDir + "/" + file.getBaseName()));
          }
        }
        result.lowerDirs.add(node.hash);
      }
    }
    return result;
  }

  private static String getNodeStashDir(PathStackNode node) {
    if (node.path == null) {
      return String.format("%s/%s", STASHED_DIR, node.hash);
    }
    return String.format("%s/%s/%s", STASHED_DIR, node.hash, node.path);
  }
}
