import pandas as pd
import sys
from preprocUtils import quantile, preprocSocial

input_file = sys.argv[1]
output_file = sys.argv[2]
year = sys.argv[3]
binstr = sys.argv[4]

df = pd.read_csv(input_file)

df["AgeStudyStart"] = pd.cut(df["AgeStudyStart"], [0, 3, 18, 35, 51, 70, 90], labels=['0-2', '3-17', '18-34', '35-50', '51-69', '70-89'], include_lowest=True, right=False)


for binning, binstr in [("_qcut", "qcut"), ("", binstr)]:
    for feature in ["PM2.5", "Ozone"]:
        for stat in ["Avg", "Max"]:
            for suffix in ["_StudyAvg", "_StudyMax", ""]:
                col = stat + "Daily" + feature + "Exposure" + suffix
                col_2 = stat + "Daily" + feature + "Exposure" + suffix + "_2"
                quantile(df, col, 5, binstr, col + binning)
                if col_2 in df.columns.values:
                    quantile(df, col_2, 5, binstr, col_2 + binning)
                else:
                    print(col_2 + " does not exist")
preprocSocial(df)

df["year"] = year

df.to_csv(output_file, index=False)
