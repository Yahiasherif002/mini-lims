create table product (
    id number(19) generated always as identity primary key,
    code varchar2(100 char) not null unique,
    name varchar2(255 char) not null
);

create table spec_item (
    id number(19) generated always as identity primary key,
    product_id number(19) not null references product (id),
    test_code varchar2(100 char) not null,
    test_name varchar2(255 char) not null,
    unit varchar2(50 char) not null,
    min_limit number,
    max_limit number,
    unique (product_id, test_code),
    check (min_limit is not null or max_limit is not null),
    check (min_limit <= max_limit)
);

create table sample (
    id number(19) generated always as identity primary key,
    product_id number(19) not null references product (id),
    batch_number varchar2(100 char) not null,
    status varchar2(20 char) not null check (status in ('IN_TESTING', 'APPROVED')),
    registered_by varchar2(100 char) not null,
    registered_at timestamp(6) with time zone not null,
    approved_by varchar2(100 char),
    approved_at timestamp(6) with time zone
);

create table test_result (
    id number(19) generated always as identity primary key,
    sample_id number(19) not null references sample (id),
    test_code varchar2(100 char) not null,
    test_name varchar2(255 char) not null,
    unit varchar2(50 char) not null,
    min_limit number,
    max_limit number,
    value number,
    out_of_spec boolean,
    entered_by varchar2(100 char),
    entered_at timestamp(6) with time zone,
    unique (sample_id, test_code)
);

create table audit_event (
    id number(19) generated always as identity primary key,
    sample_id number(19) not null references sample (id),
    test_code varchar2(100 char),
    action varchar2(50 char) not null,
    old_value varchar2(100 char),
    new_value varchar2(100 char),
    username varchar2(100 char) not null,
    reason varchar2(1000 char) not null,
    at timestamp(6) with time zone not null
);

create index audit_event_sample on audit_event (sample_id, id);

create or replace trigger audit_event_append_only
    before update or delete on audit_event
    for each row
begin
    raise_application_error(-20001, 'audit_event is append-only');
end;
/

create or replace trigger audit_event_no_truncate
    before truncate on schema
begin
    if ora_dict_obj_name = 'AUDIT_EVENT' then
        raise_application_error(-20001, 'audit_event is append-only');
    end if;
end;
/
