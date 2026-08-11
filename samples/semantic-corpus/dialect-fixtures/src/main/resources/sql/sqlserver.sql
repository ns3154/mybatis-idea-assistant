create table [users] (
    [id] bigint identity(1,1) primary key,
    [name] nvarchar(200) not null,
    [created_at] datetime2 default sysdatetime()
);
select * from [users] order by [id] offset 10 rows fetch next 20 rows only;
