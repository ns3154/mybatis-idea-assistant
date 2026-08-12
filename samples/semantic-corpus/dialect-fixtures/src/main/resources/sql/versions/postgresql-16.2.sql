-- fixture-database: postgresql
-- fixture-version: 16.2
create table "fixture_users" (
    "id" bigint generated always as identity primary key,
    "email" text not null unique,
    "profile" jsonb not null,
    "created_at" timestamp with time zone not null default current_timestamp
);
select "id", jsonb_path_query("profile", '$.contacts[*]') as "contact"
from "fixture_users"
where "profile" @@ '$.active == true'
order by "id"
limit 20 offset 10;
