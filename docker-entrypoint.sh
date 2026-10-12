#!/bin/sh
set -e

# If DATABASE_URL is provided (e.g. from Render PostgreSQL), convert to Spring JDBC format
if [ -n "$DATABASE_URL" ] && [ -z "$SPRING_DATASOURCE_URL" ]; then
  CLEAN_URL=$(echo "$DATABASE_URL" | sed -E 's|^postgres(ql)?://||')

  if echo "$CLEAN_URL" | grep -q "@"; then
    USER_PASS=$(echo "$CLEAN_URL" | sed -E 's|@.*||')
    HOST_PORT_DB=$(echo "$CLEAN_URL" | sed -E 's|.*@||')

    DB_USER=$(echo "$USER_PASS" | cut -d: -f1)
    DB_PASS=$(echo "$USER_PASS" | cut -d: -f2-)

    export SPRING_DATASOURCE_URL="jdbc:postgresql://${HOST_PORT_DB}"
    export SPRING_DATASOURCE_USERNAME="${DB_USER}"
    export SPRING_DATASOURCE_PASSWORD="${DB_PASS}"
  else
    export SPRING_DATASOURCE_URL="jdbc:postgresql://${CLEAN_URL}"
  fi
fi

exec "$@"
