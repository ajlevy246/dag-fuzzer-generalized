This file serves as a place to take notes as I make contributions to DAGger.

## Setup & Running the Framework
### `requirements.txt`
> Python3.10 seems recommended. I needed to remove versions specifications from `requirements.txt` in order for pip installation to work, due to dependency conflicts.

### `MainFuzzer.scala`
> Setting up the fuzzer logging directories is performed by the following lines:
> ```scala
> deleteDir(config.dagGenDir) // ["dag-gen/DAGs/DAGs"]
> deleteDir(config.outDir) // ["./target/dagfuzz-out/spark-scala"]
> createDir(config.outDir) // ["./target/dagfuzz-out/spark-scala"]
>```
> These utils are written in `src/main/fuzzer/utils/io/ReadWriteUtils.scala`, and use hardcoded unix commands (like `rm`) that don't work on my Windows system.
> I replaced it temporarily with:
> ```scala
> import org.apache.commons.io.FileUtils
> def deleteDir(path: String): Unit = {
> val directory = new File(path)
> if (directory.exists()) {
>   FileUtils.deleteDirectory(directory)
> }
> ```

 ### `build.sbt`
> Working with apache spark seems to be causing some dependency issues, specifically with the following line, that declares spark as a 'provided' jar:
> ```sbt
> libraryDependencies ++= Seq(
>   ...
>   "org.apache.spark" %% "spark-sql" % "3.5.0" % "provided"
> )
> ```
> This implies that the runtime environment is meant to provide this dependency, which seems to commonly be used in the context of 'spark-submit' requests. IntelliJ has a build configuration tool that seems to allow me to include this dependencies, but I don't fully understand what's going on here.

### `JDK Issues`
> After the previous fixes, running the MainFuzzer class yields an `UnsupportedOperationException`, which seems to be caused by my use of `JDK25`. It seems the project was designed around `JDK11` instead, so I'll use that from here on out.

### Data Files
> I get the following error when running:
> ```bash
> reading table call_center...
> Exception in thread "main" org.apache.spark.sql.AnalysisException: 
>     [PATH_NOT_FOUND] Path does not exist: file:/C:/Users/ajl64/IdeaProjects/dag-fuzzer-generalized/tpcds-data-5pc/call_center.
>```
> To recreate the data, I'm using Databricks' TPC-DS generator on wsl with `dsdgen`:
> ```bash
> ~/tpcds-kit/tools$ ./dsdgen -scale 1 -dir /mnt/c/Users/ajl64/IdeaProjects/dag-fuzzer-generalized/tpcds-data-5pc
> ```
> Which generates .dat files. To convert to parquet I'm using PySpark via an AI generated script that has the schemas hardcoded.
#!/usr/bin/env python3
"""
Convert TPC-DS .dat files to Parquet format for Apache Spark
Usage: python convert_tpcds_to_parquet.py <input_dir> <output_dir>
"""

```python
import sys
import os
from pathlib import Path

try:
from pyspark.sql import SparkSession
from pyspark.sql.types import *
except ImportError:
print("Error: PySpark is not installed. Installing...")
os.system("pip install pyspark --break-system-packages")
from pyspark.sql import SparkSession
from pyspark.sql.types import *

# TPC-DS table schemas
TPCDS_SCHEMAS = {
    'call_center': StructType([
        StructField('cc_call_center_sk', IntegerType(), True),
        ...
    ]),
    'catalog_page': StructType([
        StructField('cp_catalog_page_sk', IntegerType(), True),
        ...
    ]),
    ...,
}


def get_table_name_from_filename(filename):
"""Extract table name from .dat filename"""
# Remove .dat extension and any numeric suffix
name = filename.replace('.dat', '')
# Remove any trailing numbers (e.g., catalog_sales_1.dat -> catalog_sales)
import re
name = re.sub(r'_\d+$', '', name)
return name


def convert_dat_to_parquet(input_dir, output_dir, delimiter='|'):
"""Convert TPC-DS .dat files to Parquet format"""

    # Initialize Spark Session with better memory settings
    spark = SparkSession.builder \
        .appName("TPC-DS DAT to Parquet Converter") \
        .config("spark.sql.legacy.timeParserPolicy", "LEGACY") \
        .config("spark.sql.shuffle.partitions", "200") \
        .config("spark.sql.adaptive.enabled", "true") \
        .config("spark.sql.adaptive.coalescePartitions.enabled", "true") \
        .getOrCreate()
    
    print(f"Converting .dat files from {input_dir} to Parquet in {output_dir}")
    
    input_path = Path(input_dir)
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Find all .dat files
    dat_files = list(input_path.glob('*.dat'))
    
    if not dat_files:
        print(f"No .dat files found in {input_dir}")
        return
    
    print(f"Found {len(dat_files)} .dat files")
    
    # Large fact tables that need special handling
    large_tables = {'catalog_sales', 'catalog_returns', 'store_sales', 
                    'store_returns', 'web_sales', 'web_returns', 'inventory'}
    
    for dat_file in dat_files:
        table_name = get_table_name_from_filename(dat_file.name)
        print(f"\nProcessing: {dat_file.name} -> {table_name}")
        
        # Get schema for this table
        schema = TPCDS_SCHEMAS.get(table_name)
        
        if schema is None:
            print(f"  WARNING: No schema found for {table_name}, skipping...")
            continue
        
        try:
            # Read the .dat file with the appropriate schema
            df = spark.read \
                .option("delimiter", delimiter) \
                .option("header", "false") \
                .option("nullValue", "") \
                .schema(schema) \
                .csv(str(dat_file))
            
            # For large tables, repartition to avoid memory issues
            if table_name in large_tables:
                row_count = df.count()
                print(f"  Rows: {row_count} (large table - using repartitioning)")
                
                # Repartition based on size: ~500k rows per partition
                num_partitions = max(8, (row_count // 500000) + 1)
                df = df.repartition(num_partitions)
                
                # Don't show sample for very large tables to save memory
                if row_count < 1000000:
                    print(f"  Sample data:")
                    df.show(5, truncate=True)
            else:
                print(f"  Rows: {df.count()}")
                print(f"  Sample data:")
                df.show(5, truncate=True)
            
            # Write to Parquet with compression
            output_file = output_path / table_name
            df.write \
                .mode("overwrite") \
                .option("compression", "snappy") \
                .parquet(str(output_file))
            
            print(f"  ✓ Successfully wrote to {output_file}")
            
            # Clear cache to free memory
            df.unpersist()
            
        except Exception as e:
            print(f"  ✗ Error processing {dat_file.name}: {e}")
            import traceback
            traceback.print_exc()
            continue
    
    spark.stop()
    print("\n✓ Conversion complete!")


def main():
    if len(sys.argv) != 3:
        print("Usage: python convert_tpcds_to_parquet.py <input_dir> <output_dir>")
        print("\nExample:")
        print("  python convert_tpcds_to_parquet.py ./tpcds_data ./tpcds_parquet")
        sys.exit(1)
    
        input_dir = sys.argv[1]
        output_dir = sys.argv[2]
        
        if not os.path.exists(input_dir):
            print(f"Error: Input directory '{input_dir}' does not exist")
            sys.exit(1)
        
        convert_dat_to_parquet(input_dir, output_dir)


if __name__ == "__main__":
main()
```

### **NEXT STEPS**
... Convert to WSL By re-running the above steps.
