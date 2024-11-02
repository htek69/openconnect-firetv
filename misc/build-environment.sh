#!/bin/sh
# https://gitlab.com/openconnect/build-images/-/blob/master/build-img.sh

echo "ref: $CI_COMMIT_REF_NAME"
echo "namespace: $CI_PROJECT_NAMESPACE"
echo "project: $CI_PROJECT_NAME"

set -e
echo " * Logging in to $CI_REGISTRY"
podman login -u gitlab-ci-token -p "$CI_JOB_TOKEN" "$CI_REGISTRY"

echo " * Building"
podman build -t "$1" -f misc/Dockerfile .

echo " * Pushing to $CI_REGISTRY"
podman push "$1"

echo " * Logout"
podman logout "$CI_REGISTRY"

exit 0
