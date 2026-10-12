#!/bin/sh
# Runs when the nginx container starts (nginx image runs /docker-entrypoint.d/*.sh).
# Writes the runtime config the React app loads from /config.json, and a
# Content-Security-Policy that lets the page talk only to itself and the Payments API.
set -eu

API_URL="${PAYMENTS_API_URL:-http://localhost:8080}"

cat > /usr/share/nginx/html/config.json <<EOF
{"apiUrl":"${API_URL}"}
EOF

cat > /etc/nginx/csp.conf <<EOF
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ${API_URL%/}; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" always;
EOF
