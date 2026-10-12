'use strict';

const { createMock, configFromEnv } = require('./mock');

const config = configFromEnv(process.env);
const mock = createMock(config);

mock.server.listen(config.port, '0.0.0.0', () => {
  console.log(`meridian-mock listening on ${config.port}`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    mock.close().then(() => process.exit(0));
  });
}
