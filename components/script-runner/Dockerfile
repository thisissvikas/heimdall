FROM node:24.19.0-alpine
WORKDIR /sandbox
COPY sandbox.mjs /sandbox/sandbox.mjs
USER 65532:65532
ENTRYPOINT ["node", "--permission", "--allow-fs-read=/sandbox/sandbox.mjs", "--max-old-space-size=32", "/sandbox/sandbox.mjs"]
