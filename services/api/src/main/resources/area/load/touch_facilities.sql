-- 내용이 같아 고치지 않은 줄도 기준일은 이번 파일로 맞춰요. 자리표시: 1 data_date, 2 source, 3 data_date
update area.facilities set data_date = ? where source = ? and data_date is distinct from ?;
