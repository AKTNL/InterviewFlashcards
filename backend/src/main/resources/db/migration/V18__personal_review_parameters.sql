create table review_policy (
    user_id uuid not null references app_user(id),
    version bigint not null check (version > 0),
    algorithm_version varchar(60) not null,
    intervals jsonb not null check (jsonb_typeof(intervals) = 'array' and jsonb_array_length(intervals) = 5),
    reason varchar(500) not null check (length(trim(reason)) > 0),
    created_at timestamptz not null,
    primary key (user_id, version),
    unique (user_id, algorithm_version)
);

-- Legacy events deliberately retain an unknown snapshot rather than invented parameters.
alter table review_event add column algorithm_parameters jsonb not null default '{}'::jsonb;
