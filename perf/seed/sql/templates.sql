-- fetch-templates.sh로 받은 운영 본보기를 담는 임시 표. seed.sh가 이 뒤에 CSV를 COPY로 붙여 보내요
create temp table tpl_notices (
  tpl int, field text, title text, provider text, notice_type text, supply_type_text text, summary_text text,
  detail_url text, review_status text, payload jsonb, conditions jsonb, held_items jsonb, application_windows jsonb, loc text
);
create temp table tpl_location_scores (loc_no int, score_kind text, category text, score int, score_version text);
create temp table tpl_location_metrics (loc_no int, metric text, value numeric);
create temp table tpl_notice_area_scores (kind text, score numeric, calculation_version text);
create temp table tpl_benefits (
  tpl int, provider text, field text, category text, title text, agency text, categories text[], detail_url text,
  apply_url text, summary text, apply_always boolean, business_start date, business_end date, schedule_text text,
  how_to_apply text, documents text[], contact text, relevant boolean, confidence numeric, needs_review boolean,
  review_reasons text[], has_document boolean
);
create temp table tpl_benefit_conditions (
  tpl int, ordinal int, type text, value jsonb, grp int, known boolean, raw_text text, evidence text,
  confidence numeric, origin text
);
create temp table tpl_benefit_supports (
  tpl int, ordinal int, kind text, amount_max int, amount_per text, months int, rate_pct numeric, raw_text text, evidence text
);
create temp table tpl_regions (code text, level text, parent_code text, name text, full_name text, lat float8, lng float8);
