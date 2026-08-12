-- fixture-database: sqlite
-- fixture-version: 3.45.1
create table "fixture_users" (
    "id" integer primary key autoincrement,
    "email" text not null unique,
    "profile" text not null check (json_valid("profile")),
    "created_at" text not null default current_timestamp
) strict;
select "id", json_extract("profile", '$.displayName') as "display_name"
from "fixture_users"
where json_type("profile", '$.active') = 'true'
order by "id"
limit 20 offset 10;
