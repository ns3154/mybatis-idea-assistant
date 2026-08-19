drop table if exists sample_user;

create table sample_user (
    id bigint primary key,
    name varchar(100) not null,
    status varchar(20) not null
);
