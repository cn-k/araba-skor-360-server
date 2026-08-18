-- Adds optional per-category ratings alongside the existing overall `score` column.
-- `score` keeps its column/API name unchanged (no data migration needed) but now represents
-- "genel memnuniyet" (overall satisfaction) specifically, with these four as optional detail
-- breakdowns rather than a single opaque total. All nullable — a user can rate just the
-- overall score without filling in every category.
ALTER TABLE user_car_review
  ADD COLUMN interior_quality_score INTEGER CHECK (interior_quality_score BETWEEN 1 AND 100),
  ADD COLUMN powertrain_harmony_score INTEGER CHECK (powertrain_harmony_score BETWEEN 1 AND 100),
  ADD COLUMN nvh_score INTEGER CHECK (nvh_score BETWEEN 1 AND 100),
  ADD COLUMN ride_comfort_score INTEGER CHECK (ride_comfort_score BETWEEN 1 AND 100);
