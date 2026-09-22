-- Create a table
CREATE TABLE IF NOT EXISTS training_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    raw_sql TEXT NOT NULL,    -- A payload may occur in multiple source datasets
    feature_vector TEXT NOT NULL, -- Store the extracted features as a vector
    label INTEGER DEFAULT 0,    -- Label for classification (0: benign, 1: malicious)
    source TEXT NOT NULL,  -- One source dataset for this row
    split_group TEXT DEFAULT 'train',  -- Group identifier for train/val/test split
    batch_seq INTEGER,  -- Sequence number for batch processing
    is_trained INTEGER DEFAULT 0, -- Flag to determine incremental training
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- Timestamp for record creation
);
-- construct index to accelerate incremental scanning
CREATE INDEX IF NOT EXISTS idx_is_trained ON training_data(is_trained);
-- Construct index to accelerate queries based on batch sequence
CREATE INDEX IF NOT EXISTS idx_batch_seq ON training_data(batch_seq);
CREATE UNIQUE INDEX IF NOT EXISTS uq_training_source_raw_sql
    ON training_data(source, raw_sql);


