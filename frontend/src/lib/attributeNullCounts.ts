export type AttributeNullCount = {
  column: string;
  nulls: number;
};

/** SQL NULL, a missing cell, and a blank string all count as null for the dashboard KPI. */
export function isNullAttributeValue(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  return typeof value === "string" && value.trim() === "";
}

/** One null total per attribute, in the same order as the result columns. */
export function attributeNullCounts(
  columns: string[],
  rows: ReadonlyArray<Record<string, unknown>>,
): AttributeNullCount[] {
  return columns.map((column) => {
    let nulls = 0;
    for (const row of rows) {
      if (isNullAttributeValue(row[column])) nulls += 1;
    }
    return { column, nulls };
  });
}

/** Null KPI cards are shown only when a column has more than one null. */
export function visibleAttributeNullCounts(
  columns: string[],
  rows: ReadonlyArray<Record<string, unknown>>,
): AttributeNullCount[] {
  return attributeNullCounts(columns, rows).filter((item) => item.nulls > 1);
}
