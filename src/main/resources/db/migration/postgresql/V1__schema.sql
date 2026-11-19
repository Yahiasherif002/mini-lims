create table product (
    id bigint generated always as identity primary key,
    code text not null unique,
    name text not null
);

create table spec_item (
    id bigint generated always as identity primary key,
    product_id bigint not null references product (id),
    test_code text not null,
    test_name text not null,
    unit text not null,
    min_limit numeric,
    max_limit numeric,
    unique (product_id, test_code),
    check (min_limit is not null or max_limit is not null),
    check (min_limit <= max_limit)
);

create table sample (
    id bigint generated always as identity primary key,
    product_id bigint not null references product (id),
    batch_number text not null,
    status text not null check (status in ('IN_TESTING', 'APPROVED')),
    registered_by text not null,
    registered_at timestamptz not null,
    approved_by text,
    approved_at timestamptz
);

create table test_result (
    id bigint generated always as identity primary key,
    sample_id bigint not null references sample (id),
    test_code text not null,
    test_name text not null,
    unit text not null,
    min_limit numeric,
    max_limit numeric,
    value numeric,
    out_of_spec boolean,
    entered_by text,
    entered_at timestamptz,
    unique (sample_id, test_code)
);

create table audit_event (
    id bigint generated always as identity primary key,
    sample_id bigint not null references sample (id),
    test_code text,
    action text not null,
    old_value text,
    new_value text,
    username text not null,
    reason text not null,
    at timestamptz not null
);

create index audit_event_sample on audit_event (sample_id, id);

create function reject_audit_change() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only';
end
$$;

create trigger audit_event_append_only before update or delete on audit_event
    for each row execute function reject_audit_change();

create trigger audit_event_no_truncate before truncate on audit_event
    for each statement execute function reject_audit_change();
