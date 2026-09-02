#!/bin/sh
# Online-build helper. The GitHub Actions workflow installs Gradle 8.10.2 directly.
exec gradle "$@"
