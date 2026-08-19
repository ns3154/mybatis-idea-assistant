-- fixture-database: mysql
-- fixture-version: 8.0.36
create table `fixture_users` (
    `id` bigint unsigned auto_increment primary key,
    `email` varchar(320) not null,
    `profile` json not null,
    `email_domain` varchar(255)
        generated always as (substring_index(`email`, '@', -1)) stored,
    `created_at` timestamp(6) not null default current_timestamp(6),
    unique key `uk_fixture_users_email` (`email`)
);
select `id`, `profile` ->> '$.displayName' as `display_name`
from `fixture_users`
where json_contains_path(`profile`, 'one', '$.displayName')
order by `id`
limit 20 offset 10;
