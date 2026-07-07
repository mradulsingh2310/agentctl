create table agent_steps (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    step_type text not null,
    status text not null,
    summary text not null,
    input text not null,
    output_json text not null,
    error_code text,
    error_message text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, id),
    constraint agent_steps_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade
);

create table model_calls (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    step_id text not null,
    provider text not null,
    model text not null,
    input_tokens integer not null,
    output_tokens integer not null,
    cost_estimate numeric,
    created_at timestamp not null,
    primary key (tenant_id, id),
    constraint model_calls_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade
);

create table tool_calls (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    step_id text not null,
    tool_name text not null,
    operation_id text,
    status text not null,
    backend text,
    external_url text,
    fga_decision_id text,
    metadata_json text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, id),
    constraint tool_calls_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade
);

create index agent_steps_tenant_run_created_at_idx on agent_steps (tenant_id, run_id, created_at);
create index model_calls_tenant_run_created_at_idx on model_calls (tenant_id, run_id, created_at);
create index tool_calls_tenant_run_created_at_idx on tool_calls (tenant_id, run_id, created_at);
