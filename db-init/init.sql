-- init.sql
CREATE TABLE submissions (
    id SERIAL PRIMARY KEY,
    student VARCHAR(50),
    work_id INT,
    file_path TEXT
);

CREATE TABLE reports (
    id SERIAL PRIMARY KEY,
    submission_id INT NOT NULL,
    hash VARCHAR(64),
    plagiat BOOLEAN DEFAULT FALSE
);
