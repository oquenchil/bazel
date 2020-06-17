"""Tests for Starlark implementation of cc_import"""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "analysistest")
load("@rules_cc//cc:defs.bzl", "cc_binary")
load("//tools/build_defs/cc:cc_import.bzl", "cc_import")

def _cc_import_test_impl(ctx):
    env = analysistest.begin(ctx)

    target_under_test = analysistest.target_under_test(env)
    for action in target_under_test:
        print(action)

    return analysistest.end(env)

cc_import_test = analysistest.make(_cc_import_test_impl)

def _test_cc_import():
    cc_import(
        name = "cc_import_test_import_name", 
        tags = ["manual"],
        linkopts = ["-linkopt"],
        hdrs = ["mylib.h"],
        static_library = "libmylib.a",
    )

    cc_binary(
        name = "cc_import_test_binary_name",
        deps = [":cc_import_test_import_name"],
        srcs = ["source.cc"]
    )

    cc_import_test(
        name = "cc_import_test",
        target_under_test = ":cc_import_test_binary_name"
     )

def cc_import_test_suite(name):
    _test_cc_import()

    native.test_suite(
        name = name,
        tests = [
            ":cc_import_test",
        ],
    )
