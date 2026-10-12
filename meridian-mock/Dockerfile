FROM node:22-alpine
WORKDIR /app
# Owned by node so the files are readable whatever their permissions on the host
COPY --chown=node:node package.json server.js mock.js ./
USER node
EXPOSE 9090
CMD ["node", "server.js"]
