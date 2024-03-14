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
import com.google.common.base.Preconditions;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO: On linux IIUC 246 different bytes are allowed in dir names. For now we will use hashes
 *  but for a checked in implementation, more lower dirs can be squeezed per overlayfs mount using
 *  those 246 characters.
 *
 *  TODO: check if --sandbox_base works (on tmpfs too)
 */
public class DirsMerkleTree {

  private static Set<String> merkelSet = Sets.newConcurrentHashSet();
  private static final String STASHED_DIR = "_stashed_dirs";

  private static final AtomicInteger symlinksCreated = new AtomicInteger(0);
  private static final boolean DEBUG = false;

  static class Result {
    List<PathFragment> dirsToCreate = new ArrayList<>();
    List<String> lowerDirs = new ArrayList<>();

    Map<PathFragment, PathFragment>  stashedSymlinksToCreate = new HashMap<>();
  }

  static class PathStackNode {
    String basename;
    String path;
    List<PathFragment> files = new ArrayList<>();
    String hash;

    List<PathStackNode> children = new ArrayList<>();

    void computeHash() {
      Hasher hasher = Hashing.goodFastHash(32).newHasher();
      hasher.putString(path, StandardCharsets.UTF_8);
      for (PathFragment filename : files) {
        hasher.putString(filename.getBaseName(), StandardCharsets.UTF_8);
      }
      for (PathStackNode child : children) {
        hasher.putString(child.hash, StandardCharsets.UTF_8);
      }
      hash = hasher.hash().toString();
    }
  }
  // Implementation that exploits inputs being sorted
  public static Result buildTree(SandboxInputs sandboxInputs) {
    List<PathStackNode> roots = new ArrayList<>();
    List<PathStackNode> pathStack = new ArrayList<>();

    try (SilentCloseable c = Profiler.instance().profile("DirsMerkelTree.buildCurrentTree")) {
      for (var entry : sandboxInputs.getFiles().entrySet()) {
        List<String> tokenizedPath = new ArrayList<>();
        int i = 0;
        PathFragment path = entry.getKey();
          if (path.getPathString().startsWith("bazel-out")) {
            continue;
          }
          if (entry.getValue() != null && entry.getValue().asPath().isDirectory()) {
            throw new RuntimeException("plf: Some files are dirs");
          }
          // Tokenize current path
          PathFragment currPath = path.getParentDirectory();
          while (!currPath.isEmpty()) {
            tokenizedPath.add(currPath.getBaseName());
            currPath = currPath.getParentDirectory();
          }
          // Find top most stack node that is in currPath
          if (!tokenizedPath.isEmpty()) {
            tokenizedPath = Lists.reverse(tokenizedPath);
            String currSegment = tokenizedPath.get(0);
            while (i < tokenizedPath.size() && i < pathStack.size() && pathStack.get(
                i).basename.equals(
                currSegment)) {
              i++;
              if (i < tokenizedPath.size()) {
                currSegment = tokenizedPath.get(i);
              }
            }
          }

          // Pop nodes from stack which we won't see again. We know we won't see them because the
          // SandboxInputs are sorted alphabetically. We can compute the hash of the popped nodes
          // already since we won't see any other files contained within those directories.
          popPathStackNodes(pathStack, roots, i);

          // Add new nodes to the stack representing the segments of the current path
          for (int j = i; j < tokenizedPath.size(); j++) {
            PathStackNode pathStackNode = new PathStackNode();
            pathStackNode.path = Joiner.on("/").join(tokenizedPath.subList(0, j + 1));
            pathStackNode.basename = tokenizedPath.get(j);
            if (!pathStack.isEmpty()) {
              pathStack.get(pathStack.size() - 1).children.add(pathStackNode);
            }
            if (pathStack.size() == 0) {
              roots.add(pathStackNode);
            }
            pathStack.add(pathStackNode);
          }
          if (pathStack.isEmpty()) {
            // TODO: are we here when we have files in the root?
            // TODO: make this nice later, for now I just want a number
            PathStackNode pathStackNode = new PathStackNode();
            //pathStackNode.path = filename;
            pathStackNode.path = ".";
            pathStackNode.files.add(path);
            roots.add(pathStackNode);
          } else {
            pathStack.get(pathStack.size() - 1).files.add(path);
          }
      }
      popPathStackNodes(pathStack, roots, 0);
    }


      // for (PathStackNode root : roots) {
      //   debugTraversePathStackNode(root, /* depth= */ 0);
      // }

      return checkAndPopulateCache(roots);
  }

  private static void debugTraversePathStackNode(PathStackNode node, int depth) {
    if (DEBUG) {
      StringBuilder prefixBuilder = new StringBuilder();
      for (int i = 0; i < depth - 1; i++) {
        prefixBuilder.append("-");
      }
      System.out.println(prefixBuilder.toString() + node.basename);
      prefixBuilder.append("-");
      String prefix = prefixBuilder.toString();
      for (PathFragment file : node.files) {
        System.out.println(prefix + file.getBaseName());
      }
    }
    Set<String> childBasenames = Sets.newHashSet();
    childBasenames.addAll(node.files.stream().map(PathFragment::getBaseName).collect(
        ImmutableList.toImmutableList()));
    for (PathStackNode child : node.children) {
      debugTraversePathStackNode(child, depth + 1);
      childBasenames.add(child.basename);
    }
    //Preconditions.checkState(node.files.size() + node.children.size()  == childBasenames.size());
  }

  private static void popPathStackNodes(List<PathStackNode> pathStack, List<PathStackNode> roots, int toIndex) {
    while (pathStack.size() > toIndex) {
      PathStackNode pathStackNode = pathStack.remove(pathStack.size() - 1);
      pathStackNode.computeHash();
    }
  }

  private static Result checkAndPopulateCache(List<PathStackNode> roots) {
    Result result = new Result();
    for (PathStackNode root : roots) {
      if (root.basename == null) {
        // TODO: File is in root dir, add it
        throw new RuntimeException("Shouldn't be here first examples");
      } else if (merkelSet.contains(root.hash)) {
        if (DEBUG) {
          System.out.println("Mounting paths [root] for " + root.hash);
        }
        computeMountPaths(root, result.lowerDirs);
      } else {
        stashNode(root, result);
      }
    }
    return result;
  }

  private static String getNodeStashDir(PathStackNode node) {
    return String.format("%s/%s/%s", STASHED_DIR, node.hash, node.path);
  }

  private static void stashNode(PathStackNode node, Result result) {
    merkelSet.add(node.hash);

    String nodeStashDir = getNodeStashDir(node);
    // TODO: Create parents too.
    result.dirsToCreate.add(PathFragment.create(nodeStashDir));
    for (PathFragment file : node.files) {
      // TODO: Add filenames in this dir to list to be stashed, this will have to be symlinked later
      // during createFileSystem
      // TODO: Store in a table, previous real symlink path target ->  _stashed_dir target
      // TODO: instead of symlinking path to dest, we symlink the stashed dir to inputs.getFiles().get(fragment);
      if (DEBUG) {
        System.out.println("Symlink: " + file + " --> " + nodeStashDir + "/" + file.getBaseName());
      }
      result.stashedSymlinksToCreate.put(file, PathFragment.create(nodeStashDir + "/" + file.getBaseName()));

      // 3 types
      // Symlinks that are already stashed, dir gets mounted, nothing to do
      // Symlinks part of a stash that is being created now, taken care of by stashedSymlinksToCreate
      // Symlinks that aren't affected by any of this, this can be identified by them not starting with bazel-out.
    }

    addLowerDir(result.lowerDirs, node.hash);
    for (PathStackNode child : node.children) {
      if (merkelSet.contains(child.hash)) {
        if (DEBUG) {
          System.out.println("Mounting paths for " + child.hash);
        }
        computeMountPaths(child, result.lowerDirs);
      } else {
        stashNode(child, result);
      }
    }
  }

  private static void computeMountPaths(PathStackNode node, List<String> lowerDirs) {
    Preconditions.checkState(merkelSet.contains(node.hash));
    addLowerDir(lowerDirs, node.hash);
    for (PathStackNode child : node.children) {
      if (DEBUG) {
        System.out.println("Mounting paths [recursive] for " + child.hash);
      }
      computeMountPaths(child, lowerDirs);
    }
  }

  private static void addLowerDir(List<String> lowerDirs, String hash) {
    String source = STASHED_DIR + "/" + hash;
    // TODO: These paths need to be prefixed with execroot, NOT sandbox execroot which changes
    //  between actions
    if (DEBUG) {
      System.out.println("Mount path:" + source);
    }
    lowerDirs.add(source);
  }
}
