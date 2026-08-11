create table `users` (
    `id` bigint unsigned auto_increment primary key,
    `name` varchar(200) not null,
    `profile` json,
    `created_at` timestamp default current_timestamp
);
select * from `users` order by `id` limit 20 offset 10;
