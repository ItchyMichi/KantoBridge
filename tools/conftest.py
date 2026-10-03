"""Shared pytest configuration for Kanto Bridge tools.

End-to-end tests (marker ``e2e``) launch the real Gen1Recomp test instance in
``local/gen1recomp-e2e``. They run only when ``--e2e`` is given, and skip with a
reason when the pre-flight (docs/PLAN.md section 0.6) isn't done.
"""
import pytest


def pytest_addoption(parser):
    parser.addoption(
        "--e2e",
        action="store_true",
        default=False,
        help="also run end-to-end tests against local/gen1recomp-e2e",
    )


def pytest_collection_modifyitems(config, items):
    if config.getoption("--e2e"):
        return
    skip = pytest.mark.skip(reason="end-to-end test: run with --e2e")
    for item in items:
        # Not "e2e" in item.keywords: the tools/e2e directory name is a keyword too.
        if item.get_closest_marker("e2e") is not None:
            item.add_marker(skip)
