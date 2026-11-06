#!/usr/bin/env bash
set -euo pipefail

npm ci --no-audit --no-fund </dev/null
npm run prisma:generate
npm run prisma:migrate:deploy
npm run build
