#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# One database per service, all in the one MVP container.
#
# Runs ONLY when the data directory is empty (first `docker compose up` on a
# clean machine). It is not a migration mechanism — each service owns its own
# schema through its own Flyway migrations. This script only makes sure the
# empty databases and the extensions they need exist.
#
# POSTGRES_DB (explorelk_auth) is created by the image entrypoint itself, so
# only the additional databases are listed here.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

EXTRA_DATABASES="explorelk_destination"

for db in $EXTRA_DATABASES; do
    echo "  creating database '$db'"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-SQL
        CREATE DATABASE $db OWNER $POSTGRES_USER;
SQL
done

# PostGIS ships as an extension and must be enabled per database.
# pg_trgm backs the trigram index used for destination name search.
echo "  enabling postgis + pg_trgm in explorelk_destination"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "explorelk_destination" <<-SQL
    CREATE EXTENSION IF NOT EXISTS postgis;
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL
