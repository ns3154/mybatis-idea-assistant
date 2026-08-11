create table "users" (
    "id" integer primary key autoincrement,
    "name" text not null,
    "created_at" text default current_timestamp
);
select * from "users" order by "id" limit 20 offset 10;
