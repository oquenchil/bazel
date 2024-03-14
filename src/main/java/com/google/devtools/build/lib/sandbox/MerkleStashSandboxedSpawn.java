// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.util.CommandDescriptionForm;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Creates an execRoot for a Spawn that contains input files as symlinks to their original
 * destination.
 */
public class MerkleStashSandboxedSpawn extends SymlinkedSandboxedSpawn {

  private final DirsMerkleTree.Result dirsMerkleTreeResult;
  private final Path execRoot;

  private final Path sandboxPath;

  public MerkleStashSandboxedSpawn(
      DirsMerkleTree.Result dirsMerkleTreeResult,
      Path execRoot,
      Path sandboxPath,
      Path sandboxExecRoot,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      SandboxInputs inputs,
      SandboxOutputs outputs,
      Set<Path> writableDirs,
      TreeDeleter treeDeleter,
      @Nullable Path sandboxDebugPath,
      @Nullable Path statisticsPath,
      @Nullable ImmutableList<String> interactiveDebugArguments,
      String mnemonic) {
    super(
        sandboxPath,
        sandboxExecRoot,
        arguments,
        environment,
        inputs,
        outputs,
        writableDirs,
        treeDeleter,
        sandboxDebugPath,
        statisticsPath,
        interactiveDebugArguments,
        mnemonic);
    this.dirsMerkleTreeResult = dirsMerkleTreeResult;
    this.execRoot = execRoot;
    this.sandboxPath = sandboxPath;
  }

  @Override
  public void createFileSystem() throws IOException, InterruptedException {
    try (SilentCloseable c = Profiler.instance().profile("sandbox.createFileSystem")) {
      super.createFileSystem();
      for (PathFragment directory : dirsMerkleTreeResult.dirsToCreate) {
        // TODO: this can be done faster by issuing individual mkdir calls for the directories above
        //  the execroot
        execRoot.getRelative(directory).createDirectoryAndParents();
      }
      for (var symlinkEntry : dirsMerkleTreeResult.stashedSymlinksToCreate.entrySet()) {
        execRoot.getRelative(symlinkEntry.getKey())
            .createSymbolicLink(execRoot.getRelative(symlinkEntry.getValue()));
      }
    }
    // Create upperdir in C++ binary.
  }
}
