-- fixture-database: sqlserver
-- fixture-version: 2022
create table [fixture_users] (
    [id] bigint identity(1,1) primary key,
    [email] nvarchar(320) not null unique,
    [profile] nvarchar(max) not null check (isjson([profile]) = 1),
    [created_at] datetime2(6) not null default sysdatetime()
);
select [email], string_agg(convert(nvarchar(max), json_value([profile], '$.tag')), ',')
    within group (order by [id]) as [tags]
from [fixture_users]
group by [email]
order by [email]
offset 10 rows fetch next 20 rows only;
