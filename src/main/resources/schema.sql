create table if not exists payments (
                                        id            varchar(64)              primary key,
    merchant_id   varchar(64)              not null,
    amount_minor  bigint                   not null,
    currency      varchar(8)               not null,
    recorded_at   timestamp with time zone not null
);