FROM clojure:tools-deps-1.11.1.1413-focal

COPY . /driver

ARG METABASE_VERSION="v0.49.3"

RUN curl -Lo - https://github.com/metabase/metabase/archive/refs/tags/${METABASE_VERSION}.tar.gz | tar -xz && mv metabase-* /metabase

WORKDIR /driver