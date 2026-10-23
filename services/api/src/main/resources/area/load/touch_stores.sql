-- 내용이 같아 고치지 않은 점포도 기준일은 이번 파일로 맞춰요. 자리표시: 1 data_date, 2 load_scope, 3 data_date
update area.stores set data_date = ? where load_scope = ? and data_date is distinct from ?;
