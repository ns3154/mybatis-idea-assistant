-- fixture-database: oracle
-- fixture-version: 21c
create table "FIXTURE_USERS" (
    "ID" number generated always as identity primary key,
    "EMAIL" varchar2(320 char) not null unique,
    "PROFILE" json,
    "CREATED_AT" timestamp with time zone default systimestamp not null
);
select "ID", json_object('email' value "EMAIL") as "SUMMARY"
from "FIXTURE_USERS"
order by "ID"
offset 10 rows fetch next 20 rows only;
