FROM clojure:tools-deps-1.11.1.1435-bookworm

COPY . /driver

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

ARG METABASE_VERSION="v0.49.14"

RUN curl -Lo - https://github.com/metabase/metabase/archive/refs/tags/${METABASE_VERSION}.tar.gz | tar -xz && mv metabase-* /metabase

WORKDIR /driver