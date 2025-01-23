#!/bin/sh
# https://gitlab.com/openconnect/build-images/-/blob/master/build-img.sh
set -e

echo "ref: $CI_COMMIT_REF_NAME"
echo "namespace: $CI_PROJECT_NAMESPACE"
echo "project: $CI_PROJECT_NAME"

is_master=0
[ "$CI_COMMIT_REF_NAME" = "master" ] && [ "$CI_PROJECT_NAMESPACE" = "openconnect" ] && \
    [ "$CI_PROJECT_NAME" = "ics-openconnect" ] && is_master=1


echo " * Building"
podman build -t "$1" -f misc/Dockerfile .


if [ $is_master = 0 ]; then
    echo "* Not pusing to a registry (not a master build)"
    exit 0
fi

echo " * Logging in to $CI_REGISTRY"
podman login -u gitlab-ci-token -p "$CI_JOB_TOKEN" "$CI_REGISTRY"

echo " * Pushing to $CI_REGISTRY"
podman push "$1"

echo " * Logout"
podman logout "$CI_REGISTRY"

exit 0
