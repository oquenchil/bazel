// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Implements detour-based sandboxed spawn. */
public class LandlockSandboxedSpawn implements SandboxedSpawn {

  private final Path sandboxPath;
  private final Path execRoot;
  private final Path sandboxExecRoot;
  private final ImmutableMap<String, String> environment;
  private final SandboxInputs inputs;
  private final SandboxOutputs outputs;
  ImmutableList<Path> readableSystemDirs;
  private final Path landlockAccesibleFilesListPath;
  private final ImmutableSet<Path> writableDirs;
  private final ImmutableSet<PathFragment> runfilesDirs;
  private final ImmutableList<String> arguments;

  private final TreeDeleter treeDeleter;
  private final String mnemonic;

  public LandlockSandboxedSpawn(
      Path sandboxPath,
      Path execRoot,
      Path sandboxExecRoot,
      ImmutableMap<String, String> environment,
      ImmutableList<String> arguments,
      SandboxInputs inputs,
      SandboxOutputs outputs,
      ImmutableList<Path> readableSystemDirs,
      Path landlockAccesibleFilesListPath,
      ImmutableSet<Path> writableDirs,
      ImmutableSet<PathFragment> runfilesDirs,
      TreeDeleter treeDeleter,
      String mnemonic) {
    this.sandboxPath = sandboxPath;
    this.execRoot = execRoot;
    this.sandboxExecRoot = sandboxExecRoot;
    this.environment = environment;
    this.arguments = arguments;
    this.inputs = inputs;
    this.outputs = outputs;
    this.readableSystemDirs = readableSystemDirs;
    this.landlockAccesibleFilesListPath = landlockAccesibleFilesListPath;
    this.writableDirs = writableDirs;
    this.runfilesDirs = runfilesDirs;
    this.treeDeleter = treeDeleter;
    this.mnemonic = mnemonic;
  }

  @Override
  public Path getSandboxExecRoot() {
    return execRoot;
  }

  @Override
  public ImmutableList<String> getArguments() {
    return arguments;
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  @Override
  @Nullable
  public Path getStatisticsPath() {
    return null;
  }

  @Override
  public void createFileSystem() throws IOException {
    writeLandlockAccessibleFiles(
        runfilesDirs,
        landlockAccesibleFilesListPath,
        inputs,
        outputs,
        readableSystemDirs);
  }

  @Override
  public void copyOutputs(Path execRoot) throws IOException {
    SandboxHelpers.moveOutputs(outputs, sandboxExecRoot, execRoot);
  }

  @Override
  public void delete() {
    try {
      treeDeleter.deleteTree(sandboxPath);
    } catch (IOException e) {
      // Unsure when this could happen, surface the error for now.
      throw new RuntimeException(e);
    }
  }

  public static ImmutableSet<PathFragment> getOutputDirs(
      SandboxOutputs outputs) {
    return Stream.concat(outputs.dirs().stream(),
            outputs.files().stream().map(x -> x.getParentDirectory()))
        .collect(ImmutableSet.toImmutableSet());
  }

  public static ImmutableList<PathFragment> getMountPointsFromOutputDirs(
      ImmutableSet<PathFragment> outputDirs) {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    PathFragment[] sortedOutputDirs = outputDirs.toArray(new PathFragment[0]);
    Arrays.sort(sortedOutputDirs, new Comparator<PathFragment>() {
      public int compare(PathFragment idx1, PathFragment idx2) {
        return Integer.compare(idx1.segmentCount(), idx2.segmentCount());
      }
    });
    result.add(sortedOutputDirs[0]);
    for (int i = 1; i < sortedOutputDirs.length; i++) {
      boolean sawParent = false;
      for (int j = 0; j < i; j++) {
        if (sortedOutputDirs[i].startsWith(sortedOutputDirs[j])) {
          sawParent = true;
        }
      }
      if (!sawParent) {
        result.add(sortedOutputDirs[i]);
      }
    }
    return result.build();
  }

  private void writeLandlockAccessibleFiles(
      ImmutableSet<PathFragment> runfilesDirs,
      Path sandboxConfigPath,
      SandboxInputs inputs,
      SandboxOutputs outputs,
      ImmutableList<Path> readableSystemDirs) {
    for (PathFragment pathFragment : outputs.files()) {
      if (pathFragment.getPathString().endsWith("kernel-uapi-headers.tar.gz")) {
        break;
      }
    }
    try {
      ImmutableSet.Builder<Path> directoriesToCreateBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> inputPaths = ImmutableSet.builder();
      ImmutableSet<PathFragment> outputDirs = getOutputDirs(outputs);
      for (PathFragment path : outputDirs) {
        directoriesToCreateBuilder.add(sandboxExecRoot.getRelative(path));
      }
      for (Path path : directoriesToCreateBuilder.build()) {
        inputPaths.add(path.getPathString());
        path.createDirectoryAndParents();
      }
      ImmutableSet.Builder<Pair<PathFragment, Path>> inputPairs = ImmutableSet.builder();
      for (var input : inputs.getFiles().entrySet()) {
        PathFragment relativePath = input.getKey();
        Path path = null;
        for (PathFragment runfilesDir : runfilesDirs) {
          if (relativePath.startsWith(runfilesDir)) {
            path = execRoot.getRelative(relativePath);
            break;
          }
        }
        path = path == null ? input.getValue() : path;
        if (path.getPathString().endsWith("curses.h")) {
          System.out.println("break");
        }
        inputPaths.add(path.getPathString());
        // FIXME: here we want to build up the list of those that need to be hardlinked
        // We shouldn't be checking for startsWith(execRoot), we should be checking whether it starts
        // with any of the output dirs.
        // At the same time those that aren't under an output dir might also be symlinks that we
        // need to grant access to with Landlock.
        if (path.startsWith(execRoot)) {
          if (!relativePath.equals(path.relativeTo(execRoot))) {
            Preconditions.checkState(false);
          }
          inputPairs.add(Pair.of(relativePath, path));
          int i = 0;
          while (path.isSymbolicLink() && path.startsWith(execRoot)) {
            i++;
            if (i > 10) {
              break;
            }
            PathFragment symbolicLink = path.readSymbolicLink();
            if (PathFragment.isAbsolute(symbolicLink.getPathString())) {
              path = execRoot.getRelative(symbolicLink);
              if (path.getPathString().endsWith("curses.h")) {
                System.out.println("break");
              }
              inputPaths.add(path.getPathString());
              if (path.startsWith(execRoot)) {
                relativePath = path.relativeTo(execRoot);
                inputPairs.add(Pair.of(relativePath, path));
              }
            } else {
              break;
            }
          }
        }
      }

      for (var input : inputPairs.build()) {
        PathFragment relativePath = input.first;
        Path path = input.second;
        for (PathFragment outputPath : outputDirs) {
          if (relativePath.startsWith(outputPath))
            if (path.isDirectory()) {
              // FIXME: change this to create hardlinks to files instead of copying directory
              Path link = sandboxExecRoot.getRelative(relativePath);
              link.getParentDirectory().createDirectoryAndParents();;
              FileSystemUtils.copyTreesBelow(path, link, Symlinks.FOLLOW);
              break;
            } else {
              Path link = sandboxExecRoot.getRelative(relativePath);
              link.getParentDirectory().createDirectoryAndParents();;
              path.createHardLink(link);
              //FileSystemUtils.copyFile(path, link);
              break;
          }
        }
      }
      for (Path path : readableSystemDirs) {
        inputPaths.add(path.getPathString());
      }

      try (PrintWriter out =
          new PrintWriter(
              new BufferedWriter(
                  new OutputStreamWriter(sandboxConfigPath.getOutputStream(), UTF_8)))) {
        for (String path : inputPaths.build()) {
          out.println(path);
        }

        // For JS code
        //"bazel-out/k8-fastbuild/bin/prettier.config.js",
        //"bazel-out/k8-fastbuild/bin/package.json",
        //"bazel-out/k8-fastbuild/bin/.browserslistrc",
        //"bazel-out/k8-fastbuild/bin/client/web/package.json",
        //"bazel-out/k8-fastbuild/bin/client/browser/package.json",
        //"bazel-out/k8-fastbuild/bin/client/jetbrains/package.json",
        //"bazel-out/k8-fastbuild/bin/client/vscode/package.json",
        //"bazel-out/k8-fastbuild/bin/client/shared/package.json"
        for (String path : ImmutableList.of(
            "tensorflow/lite/schema/schema_generated.h",
            "tensorflow/lite/acceleration/configuration/configuration_generated.h",
            "tensorflow/lite/schema/conversion_metadata_generated.h"
        )) {
          Path missingPath = execRoot.getRelative(path);
          if (missingPath.exists()) {
            out.println(missingPath);
          }
        }
        // for (PathFragment runfilesDir : runfilesDirs) {
        //   out.println(execRoot.getRelative(runfilesDir).getPathString());
        // }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    System.out.println("Finished setting up file system");
    }
}
