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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Creates an execRoot for a Spawn that contains input files as symlinks to their original
 * destination.
 */
public class MerkleStashSandboxedSpawn extends AbstractContainerizingSandboxedSpawn {

  private final DirsMerkleTree.Result dirsMerkleTreeResult;
  private final Path execRoot;

  private final Path sandboxPath;
  private final Path upperDir;

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
        mnemonic);
    this.dirsMerkleTreeResult = dirsMerkleTreeResult;
    this.execRoot = execRoot;
    this.sandboxPath = sandboxPath;
    this.upperDir = sandboxPath.getRelative("upper");
  }

  @Override
  public void createFileSystem() throws IOException, InterruptedException {
      upperDir.createDirectoryAndParents();
      Set<String> newDirsCreated = new HashSet<>();
      List<String> newDirsWaited = new ArrayList<>();

      try {
        try (SilentCloseable c = Profiler.instance().profile("sandbox.firstLoop")) {
          for (PathFragment directory : dirsMerkleTreeResult.dirsToCreate) {
            String hash = directory.toString().substring(14, 22);
            if (!DirsMerkleTree.merkleSet.contains(hash)) {
              DirsMerkleTree.merkleSetLocks.putIfAbsent(hash, new ReentrantLock());
              Lock lock = DirsMerkleTree.merkleSetLocks.get(hash);
              if (lock.tryLock()) {
                if (!DirsMerkleTree.merkleSet.contains(hash)) {
                  try (SilentCloseable d = Profiler.instance().profile("sandbox.firstLoopCreateDir")) {
                    execRoot.getRelative(directory).createDirectoryAndParents();
                  }
                  newDirsCreated.add(hash);
                } else {
                  lock.unlock();
                }
              } else {
                newDirsWaited.add(hash);
              }
            }
          }
        }

        try (SilentCloseable c = Profiler.instance().profile("sandbox.secondLoop")) {
          for (var symlinkEntry : dirsMerkleTreeResult.stashedSymlinksToCreate.entrySet()) {
            String hash = symlinkEntry.getValue().toString().substring(14, 22);
            if (newDirsCreated.contains(hash)) {
              execRoot.getRelative(symlinkEntry.getValue())
                  .createSymbolicLink(execRoot.getRelative(symlinkEntry.getKey()));
            }
          }
        }

        try (SilentCloseable c = Profiler.instance().profile("sandbox.thirdLoop")) {
          for (String newDir : newDirsCreated) {
            DirsMerkleTree.merkleSet.add(newDir);
            DirsMerkleTree.merkleSetLocks.get(newDir).unlock();
          }
        }

        try (SilentCloseable c = Profiler.instance().profile("sandbox.fourthLoop")) {
          for (String newDir : newDirsWaited) {
            Lock lock = DirsMerkleTree.merkleSetLocks.get(newDir);
            lock.lock();
            lock.unlock();
          }
        }

        try (SilentCloseable c = Profiler.instance().profile("sandbox.fifthLoop")) {
          // Add output dirs
          for (PathFragment file : outputs.files().values()) {
            upperDir.getRelative(file.getParentDirectory()).createDirectoryAndParents();
          }
          for (PathFragment dir : outputs.dirs().values()) {
            upperDir.getRelative(dir).createDirectoryAndParents();
          }
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
  }

  @Override
  public void copyOutputs(Path execRoot) throws IOException {
    SandboxHelpers.moveOutputs(outputs, upperDir, execRoot);
  }

  @Override
  protected void copyFile(Path source, Path target) throws IOException {
    throw new IllegalStateException();
  }
}
