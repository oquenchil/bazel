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

#include <fcntl.h>
#include <linux/landlock.h>
#include <sys/prctl.h>
#include <unistd.h>

#include <fstream>
#include <iostream>
#include <string>

#include "linux-landlock.h"

#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif

#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif

#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif

#ifndef landlock_create_ruleset
static inline int landlock_create_ruleset(
    const struct landlock_ruleset_attr *const attr, const size_t size,
    const __u32 flags) {
  return syscall(__NR_landlock_create_ruleset, attr, size, flags);
}
#endif

#ifndef landlock_add_rule
static inline int landlock_add_rule(const int ruleset_fd,
                                    const enum landlock_rule_type rule_type,
                                    const void *const rule_attr,
                                    const __u32 flags) {
  return syscall(__NR_landlock_add_rule, ruleset_fd, rule_type, rule_attr,
                 flags);
}
#endif

#ifndef landlock_restrict_self
static inline int landlock_restrict_self(const int ruleset_fd,
                                         const __u32 flags) {
  return syscall(__NR_landlock_restrict_self, ruleset_fd, flags);
}
#endif

#define ENV_FS_RO_NAME "LL_FS_RO"
#define ENV_FS_RW_NAME "LL_FS_RW"
#define ENV_PATH_TOKEN ":"

/* clang-format off */

#define ACCESS_FILE ( \
        LANDLOCK_ACCESS_FS_EXECUTE | \
        LANDLOCK_ACCESS_FS_WRITE_FILE | \
        LANDLOCK_ACCESS_FS_READ_FILE | \
        LANDLOCK_ACCESS_FS_TRUNCATE)

/* clang-format on */

void allow_everything_else(int ruleset_fd) {
  struct landlock_path_beneath_attr path_beneath = {0};
  int err;

  path_beneath.allowed_access =
//      LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE |
//      LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR |
//      LANDLOCK_ACCESS_FS_TRUNCATE |
//      LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |
//      LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_DIR |
//      LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_MAKE_SOCK |
//      LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_BLOCK |
//      LANDLOCK_ACCESS_FS_MAKE_SYM;
       LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE |
       LANDLOCK_ACCESS_FS_MAKE_REG |
       LANDLOCK_ACCESS_FS_READ_DIR |
       //LANDLOCK_ACCESS_FS_TRUNCATE |
       LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |
       LANDLOCK_ACCESS_FS_MAKE_DIR |
       LANDLOCK_ACCESS_FS_MAKE_SYM;


  path_beneath.parent_fd = open("/", O_PATH | O_CLOEXEC);
  if (path_beneath.parent_fd == -1) {
    perror("Failed to open file");
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }
  err = landlock_add_rule(ruleset_fd, LANDLOCK_RULE_PATH_BENEATH, &path_beneath,
                          0);
  close(path_beneath.parent_fd);
  if (err) {
    perror("Failed to update ruleset");
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }
}

void restrict_with_landlock(int ruleset_fd, std::string path) {

  struct landlock_path_beneath_attr path_beneath = {0};
  int err;

  path_beneath.allowed_access = LANDLOCK_ACCESS_FS_EXECUTE |
      LANDLOCK_ACCESS_FS_READ_FILE;

  path_beneath.parent_fd = open(path.c_str(), O_PATH | O_CLOEXEC);
  if (path_beneath.parent_fd == -1) {
    std::string msg = std::string("Failed to open file:") + std::string(path.c_str());
    perror(msg.c_str());
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }
  err = landlock_add_rule(ruleset_fd, LANDLOCK_RULE_PATH_BENEATH, &path_beneath,
                          0);
  close(path_beneath.parent_fd);
  if (err) {
    perror("Failed to update ruleset");
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }
}

void LockInputsWithLandlock(std::string landlock_files_path) {
  if (landlock_files_path.empty()) {
    return;
  }
  //std::cout << "Hello" << std::endl;
  std::string line;

  struct landlock_ruleset_attr attr = {0};
  int ruleset_fd;

  attr.handled_access_fs =
      LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE |
      LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR |
//      LANDLOCK_ACCESS_FS_TRUNCATE |
      LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |
     LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_DIR |
      LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_MAKE_SOCK |
      LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_BLOCK |
      LANDLOCK_ACCESS_FS_MAKE_SYM;

  ruleset_fd = landlock_create_ruleset(&attr, sizeof(attr), 0);
  if (ruleset_fd == -1) {
    perror("Failed to create a ruleset");
    exit(EXIT_FAILURE);
  }
  allow_everything_else(ruleset_fd);
  std::ifstream myfile(landlock_files_path);
  if (myfile.is_open()) {
    while (getline(myfile, line)) {
//      std::cout << line << '\n';
      restrict_with_landlock(ruleset_fd, line);
    }
    myfile.close();
  }

  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) {
    perror("Failed to restrict privileges");
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }

  if (landlock_restrict_self(ruleset_fd, 0)) {
    perror("Failed to enforce ruleset");
    close(ruleset_fd);
    exit(EXIT_FAILURE);
  }
  close(ruleset_fd);
}
