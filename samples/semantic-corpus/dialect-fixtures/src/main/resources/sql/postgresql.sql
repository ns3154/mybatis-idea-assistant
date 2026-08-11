create table "users" (
    "id" bigserial primary key,
    "name" text not null,
    "profile" jsonb,
    "created_at" timestamptz default now()
);
select * from "users" order by "id" limit 20 offset 10 returning "id";
