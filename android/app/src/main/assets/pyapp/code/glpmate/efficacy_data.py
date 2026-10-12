"""由 tools/sync_efficacy.py 从核验过的说明书 JSON 自动生成——请勿手改。

源文件：glp1_labels/glp1_weight_efficacy_verified.json
生成方式：pdftotext 抽取厂商说明书 PDF 原文 → 逐条带 quote 核验 → 本脚本机械映射。

改数据的正确做法：改核验 JSON（或重新核验），然后重跑同步脚本。
手改本文件会在下次同步时被覆盖，而且会丢掉"每个数字对应哪句原文"的链。
"""

from __future__ import annotations

from typing import Any

VERIFIED_ON = '2026-10-12'

EFFICACY: dict[str, dict[str, Any]] = {'tirzepatide': {'drug_id': 'tirzepatide',
                 'brand_names': ['Zepbound (weight management)', 'Mounjaro (type 2 diabetes)'],
                 'indications': [{'kind': 'weight',
                                  'trial': "SURMOUNT-1 (labelled 'Study 1', NCT04184622)",
                                  'population': 'Adults with obesity (BMI >= 30 kg/m2), or overweight (BMI 27 to <30 '
                                                'kg/m2) with at least one weight-related comorbid condition '
                                                '(dyslipidemia, hypertension, obstructive sleep apnea or CV '
                                                'disease); patients with type 2 diabetes mellitus were excluded. '
                                                'N=2539 randomised 1:1:1:1. Mean baseline weight 104.8 kg, mean BMI '
                                                '38 kg/m2.',
                                  'duration_weeks': 72,
                                  'estimand': 'Intention-to-Treat (ITT) population = all randomised patients. '
                                              'Missing Week 72 body weight was imputed by a hybrid approach using '
                                              'retrieved dropouts from the same treatment group (non-COVID '
                                              'missingness) or all non-missing data assuming missing at random '
                                              '(COVID-19-only missingness). Missingness at Week 72: placebo 21.6%, 5 '
                                              'mg 10.2%, 10 mg 10.5%, 15 mg 9.4%. The label does NOT report an '
                                              'on-treatment estimand for these endpoints; percent changes are LS '
                                              'means from ANCOVA.',
                                  'arms': [{'dose_mg': 'placebo',
                                            'n': 643,
                                            'weight_change_pct': -3.1,
                                            'ge5_pct': 34.5,
                                            'ge10_pct': 18.8,
                                            'ge15_pct': 8.8,
                                            'ge20_pct': 3.1},
                                           {'dose_mg': 5,
                                            'n': 630,
                                            'weight_change_pct': -15.0,
                                            'ge5_pct': 85.1,
                                            'ge10_pct': 68.5,
                                            'ge15_pct': 48.0,
                                            'ge20_pct': 30.0},
                                           {'dose_mg': 10,
                                            'n': 636,
                                            'weight_change_pct': -19.5,
                                            'ge5_pct': 88.9,
                                            'ge10_pct': 78.1,
                                            'ge15_pct': 66.6,
                                            'ge20_pct': 50.1},
                                           {'dose_mg': 15,
                                            'n': 630,
                                            'weight_change_pct': -20.9,
                                            'ge5_pct': 90.9,
                                            'ge10_pct': 83.5,
                                            'ge15_pct': 70.6,
                                            'ge20_pct': 56.7}],
                                  'between_arm_differences_vs_placebo': {'weight_change_pct': {'5_mg': '-11.9 '
                                                                                                       '(-13.4, '
                                                                                                       '-10.4)',
                                                                                               '10_mg': '-16.4 '
                                                                                                        '(-17.9, '
                                                                                                        '-14.8)',
                                                                                               '15_mg': '-17.8 '
                                                                                                        '(-19.3, '
                                                                                                        '-16.3)'},
                                                                         'note': '>=15% and >=20% responder '
                                                                                 'differences for the 5 mg arm are '
                                                                                 "flagged 'e' in the label = 'Not "
                                                                                 "controlled for type I error rate'. "
                                                                                 'The 5 mg >=10% difference is also '
                                                                                 "flagged 'e'. All 10 mg and 15 mg "
                                                                                 'responder differences are flagged '
                                                                                 "'d' = p<0.001, controlled for type "
                                                                                 'I error rate.'},
                                  'additional_studies': {'SURMOUNT-2': {'label_name': 'Study 2 (NCT04657003)',
                                                                        'population': 'Adults with BMI >= 27 kg/m2 '
                                                                                      'AND type 2 diabetes mellitus, '
                                                                                      'HbA1c 7-10%, on diet/exercise '
                                                                                      'alone or oral agents (DPP-4 '
                                                                                      'inhibitors and GLP-1 RAs '
                                                                                      'excluded; insulin excluded). '
                                                                                      'N=938. Mean baseline weight '
                                                                                      '100.7 kg, BMI 36.1 kg/m2.',
                                                                        'duration_weeks': 72,
                                                                        'arms': [{'dose_mg': 'placebo',
                                                                                  'n': 315,
                                                                                  'weight_change_pct': -3.2,
                                                                                  'ge5_pct': 32.5,
                                                                                  'ge10_pct': 9.5,
                                                                                  'ge15_pct': 2.7,
                                                                                  'ge20_pct': 1.0},
                                                                                 {'dose_mg': 10,
                                                                                  'n': 312,
                                                                                  'weight_change_pct': -12.8,
                                                                                  'ge5_pct': 79.2,
                                                                                  'ge10_pct': 60.5,
                                                                                  'ge15_pct': 39.7,
                                                                                  'ge20_pct': 21.5},
                                                                                 {'dose_mg': 15,
                                                                                  'n': 311,
                                                                                  'weight_change_pct': -14.7,
                                                                                  'ge5_pct': 82.8,
                                                                                  'ge10_pct': 64.8,
                                                                                  'ge15_pct': 48.0,
                                                                                  'ge20_pct': 30.8}],
                                                                        'discontinuation_of_study_drug_any_cause': {'placebo_pct': 14.9,
                                                                                                                    '10_mg_pct': 9.3,
                                                                                                                    '15_mg_pct': 13.8}},
                                                         'SURMOUNT-3': {'label_name': 'Study 3 (NCT04657016)',
                                                                        'population': 'Adults with '
                                                                                      'obesity/overweight (same BMI '
                                                                                      'criteria, no diabetes) who '
                                                                                      'FIRST lost >=5% body weight '
                                                                                      'during a 12-week intensive '
                                                                                      'lifestyle lead-in. 806 '
                                                                                      'enrolled, 579 randomised. '
                                                                                      'Baseline here = Week 0 (after '
                                                                                      'lead-in), mean 101.9 kg.',
                                                                        'duration_weeks': 72,
                                                                        'arms': [{'dose_mg': 'placebo',
                                                                                  'n': 292,
                                                                                  'weight_change_pct': 2.5,
                                                                                  'ge5_pct': 16.5,
                                                                                  'ge10_pct': 8.9,
                                                                                  'ge15_pct': 4.2,
                                                                                  'ge20_pct': 2.2},
                                                                                 {'dose_mg': '10 or 15 (MTD)',
                                                                                  'n': 287,
                                                                                  'weight_change_pct': -18.4,
                                                                                  'ge5_pct': 87.5,
                                                                                  'ge10_pct': 76.7,
                                                                                  'ge15_pct': 65.4,
                                                                                  'ge20_pct': 44.7}],
                                                                        'note': 'Change is measured from '
                                                                                'randomisation (Week 0, after the '
                                                                                'lifestyle lead-in), NOT from the '
                                                                                'original baseline. Average '
                                                                                'lifestyle-induced loss during '
                                                                                'lead-in was 6.9%.'},
                                                         'SURMOUNT-4': {'label_name': 'Study 4 (NCT04660643)',
                                                                        'population': 'Adults with '
                                                                                      'obesity/overweight (no '
                                                                                      'diabetes). Open-label '
                                                                                      'tirzepatide 36 weeks, then '
                                                                                      'randomised withdrawal to '
                                                                                      'continue tirzepatide MTD (10 '
                                                                                      'or 15 mg) or switch to '
                                                                                      'placebo for 52 weeks. 670 '
                                                                                      'randomised.',
                                                                        'duration_weeks': 88,
                                                                        'arms': [{'dose_mg': 'placebo (withdrawal)',
                                                                                  'n': 335},
                                                                                 {'dose_mg': '10 or 15 (MTD, '
                                                                                             'continuation)',
                                                                                  'n': 335}],
                                                                        'note': 'Primary endpoint is % change from '
                                                                                'Week 36 (randomisation) to Week 88. '
                                                                                'Mean loss during the 36-week '
                                                                                'open-label lead-in was 20.9%.'}},
                                  'cardiovascular_outcomes': None,
                                  'sources': [{'claim': 'SURMOUNT-1 design, population and duration (72 weeks; BMI '
                                                        '>=30 or >=27 with comorbidity; diabetes excluded; N=2539)',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                               'section': 'PDF p.15 (section 14.1)',
                                               'quote': 'Study 1 (NCT04184622) was a 72-week trial that enrolled '
                                                        '2,539 adult patients with obesity (BMI >=30 kg/m2), or with '
                                                        'overweight (BMI 27 to <30 kg/m2) and at least one '
                                                        'weight-related comorbid condition, such as dyslipidemia, '
                                                        'hypertension, obstructive sleep apnea, or CV disease; '
                                                        'patients with type 2 diabetes mellitus were excluded.'},
                                              {'claim': 'All SURMOUNT-1 body-weight results at Week 72 (mean % '
                                                        'change and >=5/10/15/20% responders) for placebo and '
                                                        '5/10/15 mg',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                               'section': 'PDF p.16 (Table 2)',
                                               'quote': 'Table 2: Changes in Body Weight at Week 72 in Studies 1 and '
                                                        '2 in Patients with Obesity or Overweight ... '
                                                        'Intention-to-Treat (ITT) Population ... Placebo N = 643 | '
                                                        'ZEPBOUND 5 mg N = 630 | ZEPBOUND 10 mg N = 636 | ZEPBOUND '
                                                        '15 mg N = 630 ... % Change from baseline -3.1 | -15.0 | '
                                                        '-19.5 | -20.9 ... % of Patients losing >=5% body weight '
                                                        '34.5 | 85.1 | 88.9 | 90.9 ... % of Patients losing >=10% '
                                                        'body weight 18.8 | 68.5 | 78.1 | 83.5 ... % of Patients '
                                                        'losing >=15% body weight 8.8 | 48.0 | 66.6 | 70.6 ... % of '
                                                        'Patients losing >=20% body weight 3.1 | 30.0 | 50.1 | 56.7'},
                                              {'claim': 'Missing-data handling / estimand definition',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                               'section': 'PDF p.16 (Table 2 footnote a)',
                                               'quote': 'The intention-to-treat population includes all randomly '
                                                        'assigned patients. For Study 1 at Week 72, body weight was '
                                                        'missing for 21.6%, 10.2%, 10.5%, and 9.4% of patients '
                                                        'randomly assigned to placebo, ZEPBOUND 5 mg, 10 mg, and 15 '
                                                        'mg, respectively. ... The missing values were imputed by a '
                                                        'hybrid approach using retrieved dropouts from the same '
                                                        'treatment group (if missing not due to COVID-19) or using '
                                                        'all non-missing data assuming missing at random (for '
                                                        'missing solely due to COVID-19).'},
                                              {'claim': 'Type I error control caveat for the 5 mg arm responder '
                                                        'endpoints',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                               'section': 'PDF p.16 (Table 2 footnotes d and e)',
                                               'quote': 'd p-value<0.001 (unadjusted 2-sided) for superiority, '
                                                        'controlled for type I error rate. e Not controlled for type '
                                                        'I error rate.'},
                                              {'claim': 'Study 3 (SURMOUNT-3) results and Study 4 (SURMOUNT-4) '
                                                        'results',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                               'section': 'PDF p.23 (Table 4) and p.26 (Table 6)',
                                               'quote': 'Table 4: Changes in Body Weight After 12-Week Intensive '
                                                        'Lifestyle Intervention Lead-In Followed by Randomized '
                                                        'Treatment and a Standard Lifestyle Intervention (Study 3) '
                                                        '... % Change from randomization at Week 72: 2.5 (placebo) | '
                                                        '-18.4 (ZEPBOUND MTD) ... Table 6: Changes in Body Weight '
                                                        'After 36-Week Open-Label Treatment Followed by Randomized '
                                                        'Withdrawal (Study 4) ... % change from Week 36 at Week 88: '
                                                        '14.0 (placebo) | -5.5 (ZEPBOUND MTD 10 mg or 15 mg)'}],
                                  'verified': True},
                                 {'kind': 'diabetes',
                                  'trial': 'SURPASS-1 (monotherapy), SURPASS-2 (vs semaglutide 1 mg), SURPASS-3 (vs '
                                           'insulin degludec), SURPASS-4 (vs insulin glargine), SURPASS-5 (add-on to '
                                           'basal insulin vs placebo)',
                                  'population': 'Adults with type 2 diabetes mellitus and inadequate glycaemic '
                                                'control (see per-trial text). Note: this indication is glycaemic '
                                                'control - the label reports body weight only in KILOGRAMS, not as '
                                                'percent change, and reports NO >=5/10/15/20% weight-loss responder '
                                                'rates.',
                                  'duration_weeks': '40 (SURPASS-1, -2, -5), 52 (SURPASS-3, -4 primary time point; '
                                                    'SURPASS-4 continued to 104 weeks)',
                                  'estimand': 'Modified intent-to-treat (mITT) = all randomised participants exposed '
                                              'to at least 1 dose of study drug, excluding patients who discontinued '
                                              'because they did not meet enrolment criteria. Missing data imputed '
                                              'using multiple imputation with retrieved dropout (SURPASS-1 and -5 '
                                              'used placebo-based multiple imputation). Rescue medication use is '
                                              'reported per arm.',
                                  'arms': [{'dose_mg': 'placebo',
                                            'n': 113,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -1.0,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -0.1},
                                           {'dose_mg': 5,
                                            'n': 121,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -6.3,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -1.8},
                                           {'dose_mg': 10,
                                            'n': 121,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -7.0,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -1.7},
                                           {'dose_mg': 15,
                                            'n': 120,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -7.8,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -1.7},
                                           {'dose_mg': 'semaglutide 1 mg',
                                            'n': 468,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -5.7,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -1.9},
                                           {'dose_mg': 5,
                                            'n': 470,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -7.6,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -2.0},
                                           {'dose_mg': 10,
                                            'n': 469,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -9.3,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -2.2},
                                           {'dose_mg': 15,
                                            'n': 469,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -11.2,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -2.3},
                                           {'dose_mg': 'insulin degludec',
                                            'n': 359,
                                            'weight_change_kg': 1.9,
                                            'hba1c_change_pct': -1.3},
                                           {'dose_mg': 5,
                                            'n': 358,
                                            'weight_change_kg': -7.0,
                                            'hba1c_change_pct': -1.9},
                                           {'dose_mg': 10,
                                            'n': 360,
                                            'weight_change_kg': -9.6,
                                            'hba1c_change_pct': -2.0},
                                           {'dose_mg': 15,
                                            'n': 358,
                                            'weight_change_kg': -11.3,
                                            'hba1c_change_pct': -2.1},
                                           {'dose_mg': 'insulin glargine',
                                            'n': 998,
                                            'weight_change_kg': 1.7,
                                            'hba1c_change_pct': -1.4},
                                           {'dose_mg': 5,
                                            'n': 328,
                                            'weight_change_kg': -6.4,
                                            'hba1c_change_pct': -2.1},
                                           {'dose_mg': 10,
                                            'n': 326,
                                            'weight_change_kg': -8.9,
                                            'hba1c_change_pct': -2.3},
                                           {'dose_mg': 15,
                                            'n': 337,
                                            'weight_change_kg': -10.6,
                                            'hba1c_change_pct': -2.4},
                                           {'dose_mg': 'placebo',
                                            'n': 119,
                                            'weight_change_kg': 1.6,
                                            'hba1c_change_pct': -0.9},
                                           {'dose_mg': 5,
                                            'n': 116,
                                            'weight_change_kg': -5.4,
                                            'hba1c_change_pct': -2.1},
                                           {'dose_mg': 10,
                                            'n': 118,
                                            'weight_change_kg': -7.5,
                                            'hba1c_change_pct': -2.4},
                                           {'dose_mg': 15,
                                            'n': 118,
                                            'weight_change_kg': -8.8,
                                            'hba1c_change_pct': -2.3}],
                                  'between_arm_differences_vs_placebo': None,
                                  'additional_studies': None,
                                  'cardiovascular_outcomes': {'trial': 'SURPASS-CVOT (NCT04255433): tirzepatide up '
                                                                       'to 15 mg vs dulaglutide 1.5 mg, 13,299 '
                                                                       'adults with T2D and established CV disease, '
                                                                       'median follow-up 210.1 weeks',
                                                              'mace3_hr': '0.92 (95.3% CI 0.83, 1.01)',
                                                              'interpretation': 'Non-inferior to dulaglutide; '
                                                                                'superiority to dulaglutide was NOT '
                                                                                'established (p=0.007 for '
                                                                                'non-inferiority).',
                                                              'components': {'cv_death': '409 (6.2%) dulaglutide vs '
                                                                                         '367 (5.5%) tirzepatide; HR '
                                                                                         '0.89 (0.77, 1.02)',
                                                                             'non_fatal_mi': '310 (4.7%) vs 292 '
                                                                                             '(4.4%); HR 0.93 (0.80, '
                                                                                             '1.09)',
                                                                             'non_fatal_stroke': '221 (3.3%) vs 215 '
                                                                                                 '(3.2%); HR 0.97 '
                                                                                                 '(0.80, 1.16)',
                                                                             'all_cause_death': '670 (10.1%) vs 567 '
                                                                                                '(8.5%); HR 0.84 '
                                                                                                '(0.75, 0.94)'},
                                                              'note': 'Component endpoints and all-cause death were '
                                                                      "'not controlled for family-wise type I error "
                                                                      "rate'."},
                                  'sources': [{'claim': 'SURPASS-1 HbA1c and body-weight results at Week 40',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.16 (Table 4)',
                                               'quote': 'Table 4: Results at Week 40 in a Trial of MOUNJARO as '
                                                        'Monotherapy in Adult Patients with Type 2 Diabetes Mellitus '
                                                        'with Inadequate Glycemic Control with Diet and Exercise ... '
                                                        'Change at Week 40: -0.1 (placebo) | -1.8 (5 mg) | -1.7 (10 '
                                                        'mg) | -1.7 (15 mg); Body Weight (kg) Change at Week 40: '
                                                        '-1.0 | -6.3 | -7.0 | -7.8'},
                                              {'claim': 'SURPASS-2 HbA1c and body-weight results at Week 40 vs '
                                                        'semaglutide 1 mg',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.17 (Table 5)',
                                               'quote': 'Table 5: Results at Week 40 in a Trial of MOUNJARO versus '
                                                        'Semaglutide 1 mg in Adult Patients with Type 2 Diabetes '
                                                        'Mellitus Added to Metformin ... Change at Week 40: -1.9 '
                                                        '(semaglutide 1 mg) | -2.0 (5 mg) | -2.2 (10 mg) | -2.3 (15 '
                                                        'mg) ... Body Weight (kg) Change at Week 40: -5.7 | -7.6 | '
                                                        '-9.3 | -11.2'},
                                              {'claim': 'SURPASS-3 and SURPASS-4 results at Week 52',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.18-19 (Tables 6 and 7)',
                                               'quote': 'Table 6: Results at Week 52 in a Trial of MOUNJARO versus '
                                                        'Insulin Degludec ... Change at Week 52: -1.3 (insulin '
                                                        'degludec) | -1.9 (5 mg) | -2.0 (10 mg) | -2.1 (15 mg) ... '
                                                        'Body Weight (kg) Change at Week 52: 1.9 | -7.0 | -9.6 | '
                                                        '-11.3. Table 7: Results at Week 52 in a Trial of MOUNJARO '
                                                        'versus Insulin Glargine ... Change at Week 52: -1.4 '
                                                        '(insulin glargine) | -2.1 (5 mg) | -2.3 (10 mg) | -2.4 (15 '
                                                        'mg) ... Body Weight (kg) Change at Week 52: 1.7 | -6.4 | '
                                                        '-8.9 | -10.6'},
                                              {'claim': 'SURPASS-5 results at Week 40 (add-on to basal insulin)',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.20 (Table 8)',
                                               'quote': 'Table 8: Results at Week 40 in a Trial of MOUNJARO Added to '
                                                        'Basal Insulin with or without Metformin in Adult Patients '
                                                        'with Type 2 Diabetes Mellitus ... Change at Week 40: -0.9 '
                                                        '(placebo) | -2.1 (5 mg) | -2.4 (10 mg) | -2.3 (15 mg) ... '
                                                        'Body Weight (kg) Change at Week 40: 1.6 | -5.4 | -7.5 | '
                                                        '-8.8'},
                                              {'claim': 'SURPASS-CVOT primary MACE-3 result',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.22-23 (section 14.6 and Table 10)',
                                               'quote': 'Over a median follow-up of 210.1 weeks, MOUNJARO was '
                                                        'non-inferior to dulaglutide for reducing the occurrence of '
                                                        'MACE-3. The estimated hazard ratio for time to first MACE '
                                                        'was 0.92 (95.3% CI: 0.83, 1.01) for MOUNJARO compared to '
                                                        'dulaglutide. Superiority to dulaglutide was not '
                                                        'established.'},
                                              {'claim': 'No percent weight change or weight-loss responder rates in '
                                                        'the Mounjaro label',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                               'section': 'PDF p.15-21 (sections 14.2-14.4)',
                                               'quote': 'Body Weight (kg) Baseline (mean) ... Change at Week 40 ... '
                                                        '(all body-weight entries in Tables 4-8 are in kilograms; '
                                                        'the label reports no % change from baseline and no '
                                                        'proportions of patients achieving >=5%, >=10%, >=15% or '
                                                        '>=20% weight loss)'}],
                                  'verified': True}],
                 'gi_side_effects': {'trial': 'Pool of two placebo-controlled weight-reduction trials (Study 1 = '
                                              'SURMOUNT-1 and Study 2 = SURMOUNT-2); 2,519 ZEPBOUND-treated adults, '
                                              'up to 72 weeks',
                                     'arms': [{'dose_mg': 'placebo',
                                               'n': 958,
                                               'nausea_pct': 8,
                                               'vomiting_pct': 2,
                                               'diarrhoea_pct': 8,
                                               'constipation_pct': 5,
                                               'discontinuation_due_to_ae_pct': 3.4,
                                               'gi_ae_discontinuation_pct': 0.5,
                                               'any_gi_ae_pct': 30},
                                              {'dose_mg': 5,
                                               'n': 630,
                                               'nausea_pct': 25,
                                               'vomiting_pct': 8,
                                               'diarrhoea_pct': 19,
                                               'constipation_pct': 17,
                                               'discontinuation_due_to_ae_pct': 4.8,
                                               'gi_ae_discontinuation_pct': 1.9,
                                               'any_gi_ae_pct': 56},
                                              {'dose_mg': 10,
                                               'n': 948,
                                               'nausea_pct': 29,
                                               'vomiting_pct': 11,
                                               'diarrhoea_pct': 21,
                                               'constipation_pct': 14,
                                               'discontinuation_due_to_ae_pct': 6.3,
                                               'gi_ae_discontinuation_pct': 3.3,
                                               'any_gi_ae_pct': 56},
                                              {'dose_mg': 15,
                                               'n': 941,
                                               'nausea_pct': 28,
                                               'vomiting_pct': 13,
                                               'diarrhoea_pct': 23,
                                               'constipation_pct': 11,
                                               'discontinuation_due_to_ae_pct': 6.7,
                                               'gi_ae_discontinuation_pct': 4.3,
                                               'any_gi_ae_pct': 56}],
                                     'leading_discontinuation_reactions': None,
                                     'other_reactions_of_note': 'Abdominal pain 9/9/10% vs 5%; dyspepsia 9/9/10% vs '
                                                                '4%; injection-site reactions 6/8/8% vs 2%; hair '
                                                                'loss 5/4/5% vs 1%; hypotension 1/1/2% vs 0%. In '
                                                                'Study 3, 10% of ZEPBOUND-treated patients and 2% of '
                                                                'placebo-treated patients discontinued drug due to '
                                                                'adverse reactions; in Study 4, 7% discontinued '
                                                                'ZEPBOUND before randomised withdrawal at Week 36 '
                                                                'due to adverse reactions.',
                                     'sources': [{'claim': 'Zepbound discontinuation due to adverse reactions and '
                                                           'common adverse reactions >=2%',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                                  'section': 'PDF p.7 (section 6.1, Table 1)',
                                                  'quote': 'Across both trials, 4.8%, 6.3%, and 6.7% of patients '
                                                           'treated with 5 mg, 10 mg, and 15 mg of ZEPBOUND, '
                                                           'respectively, permanently discontinued treatment as a '
                                                           'result of adverse reactions compared to 3.4% of patients '
                                                           'treated with placebo. ... Adverse Reaction Placebo '
                                                           '(N=958) | ZEPBOUND 5 mg (N=630) | ZEPBOUND 10 mg (N=948) '
                                                           '| ZEPBOUND 15 mg (N=941): Nausea 8 | 25 | 29 | 28; '
                                                           'Diarrhea 8 | 19 | 21 | 23; Vomiting 2 | 8 | 11 | 13; '
                                                           'Constipation 5 | 17 | 14 | 11'},
                                                 {'claim': 'Zepbound gastrointestinal adverse reaction rates and GI '
                                                           'discontinuations',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf',
                                                  'section': 'PDF p.8 (section 6.1)',
                                                  'quote': 'In ZEPBOUND clinical trials, gastrointestinal adverse '
                                                           'reactions occurred more frequently among patients '
                                                           'receiving ZEPBOUND (5 mg 56%, 10 mg 56%, 15 mg 56%) than '
                                                           'placebo (30%). More patients receiving ZEPBOUND 5 mg '
                                                           '(1.9%), ZEPBOUND 10 mg (3.3%), and ZEPBOUND 15 mg (4.3%) '
                                                           'discontinued treatment due to gastrointestinal adverse '
                                                           'reactions than patients receiving placebo (0.5%).'},
                                                 {'claim': 'Mounjaro common adverse reactions and GI '
                                                           'discontinuations',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf',
                                                  'section': 'PDF p.7 (section 6.1, Table 1)',
                                                  'quote': 'Table 1: Adverse Reactions in Pool of Placebo-Controlled '
                                                           'Trials Reported in >=5% of MOUNJARO-treated Adult '
                                                           'Patients with Type 2 Diabetes Mellitus ... Nausea 4 | 12 '
                                                           '| 15 | 18; Diarrhea 9 | 12 | 13 | 17; Vomiting 2 | 5 | 5 '
                                                           '| 9; Constipation 1 | 6 | 6 | 7 ... More patients '
                                                           'receiving MOUNJARO 5 mg (3.0%), MOUNJARO 10 mg (5.4%), '
                                                           'and MOUNJARO 15 mg (6.6%) discontinued treatment due to '
                                                           'gastrointestinal adverse reactions than patients '
                                                           'receiving placebo (0.4%).'}],
                                     'verified': True},
                 'body_composition': {'lean_mass_pct_of_weight_loss': 25,
                                      'fat_mass_pct_of_weight_loss': 75,
                                      'source': 'Look M, Dunn JP, Kushner RF, Cao D, Harris C, Gibble TH, Stefanski '
                                                'A, Griffin R. Body composition changes during weight reduction with '
                                                'tirzepatide in the SURMOUNT-1 study of adults with obesity or '
                                                'overweight. Diabetes Obes Metab. 2025 May;27(5):2720-2729. '
                                                'doi:10.1111/dom.16275 (PMID 39996356; PMCID PMC11965027).',
                                      'notes': 'The LABEL does not quantify lean vs fat mass - section 12.2 of the '
                                               "Zepbound label states only qualitatively: 'Tirzepatide lowers body "
                                               "weight with greater fat mass loss than lean mass loss.' (PDF p.13). "
                                               'The quantitative figure comes from the DXA substudy of SURMOUNT-1 '
                                               '(n=160 with baseline and Week 72 DXA: pooled tirzepatide n=124, '
                                               "placebo n=36). Verbatim from the published abstract: 'The change in "
                                               'body weight, fat mass and lean mass from baseline to Week 72 was '
                                               '-21.3%, -33.9% and -10.9% with tirzepatide and -5.3%, -8.2% and '
                                               '-2.6% with placebo, respectively (p < 0.001 for all comparisons). Of '
                                               'the body weight lost, approximately 75% was fat mass and 25% was '
                                               "lean mass for both tirzepatide and placebo.' NOTE: this 25% "
                                               'lean-mass fraction is the same for tirzepatide and placebo, i.e. the '
                                               'drug did not change the COMPOSITION of the weight lost in this '
                                               'substudy. Also note the substudy population (mean weight 102.5 kg, '
                                               'BMI 38.0) is not identical to the full SURMOUNT-1 population.'},
                 'notes': 'Zepbound percent-weight-change values are LS means from ANCOVA on the ITT population with '
                          'hybrid imputation; they are NOT on-treatment/observed-case values. Because 21.6% of '
                          'placebo patients had missing Week 72 weight versus ~10% on tirzepatide, the ITT-based '
                          'placebo value (-3.1%) already incorporates retrieved-dropout imputation; an observed-case '
                          'or on-treatment analysis would give different numbers, which the label does not provide. '
                          'SURMOUNT-1 enrolled almost no patients with type 2 diabetes (excluded), so these numbers '
                          'must not be quoted as expected weight loss in a patient with diabetes - use SURMOUNT-2 '
                          '(-14.7% at 15 mg) for that population. Mounjaro (the diabetes brand) reports NO percent '
                          'weight change and no weight-loss responder rates anywhere in its label.',
                 'confidence': 'high',
                 'label_urls': ['https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/217806s042lbl.pdf '
                                '(Zepbound, current, Reference ID 5751110)',
                                'https://www.accessdata.fda.gov/drugsatfda_docs/label/2024/217806s003lbl.pdf '
                                '(Zepbound, 2024 version supplied by the requester; identical weight-efficacy '
                                'numbers)',
                                'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215866s044s045lbl.pdf '
                                '(Mounjaro, Reference ID 5860252)']},
 'semaglutide': {'drug_id': 'semaglutide',
                 'brand_names': [],
                 'indications': [{'kind': 'weight',
                                  'trial': "STEP 1 (labelled 'Study 2', NCT03548935)",
                                  'population': 'Adults with obesity (BMI >= 30 kg/m2), or overweight (BMI 27 to '
                                                '29.9 kg/m2) with at least one weight-related comorbid condition '
                                                '(treated or untreated dyslipidemia or hypertension); patients with '
                                                'type 2 diabetes mellitus were excluded. N=1,961 randomised 2:1. '
                                                'Mean baseline weight 105.3 kg, mean BMI 37.9 kg/m2.',
                                  'duration_weeks': 68,
                                  'estimand': 'Intention-to-treat population = all randomised patients. Missing Week '
                                              '68 body weight was imputed from retrieved subjects of the same '
                                              'randomised treatment arm (RD-MI). Missingness: Study 2 (STEP 1) 7.2% '
                                              '(WEGOVY) and 11.9% (placebo). The label reports no separate '
                                              'on-treatment estimand.',
                                  'arms': [{'dose_mg': 'placebo',
                                            'n': 655,
                                            'weight_change_pct': -2.4,
                                            'ge5_pct': 31.1,
                                            'ge10_pct': 12.0,
                                            'ge15_pct': 4.8,
                                            'ge20_pct': 1.7},
                                           {'dose_mg': 2.4,
                                            'n': 1306,
                                            'weight_change_pct': -14.9,
                                            'ge5_pct': 83.5,
                                            'ge10_pct': 66.1,
                                            'ge15_pct': 47.9,
                                            'ge20_pct': 30.2}],
                                  'between_arm_differences_vs_placebo': {'weight_change_pct': '-12.4 (-13.3, -11.6)',
                                                                         'ge5_pct': '52.4 (48.1, 56.7)',
                                                                         'ge10_pct': '54.1 (50.4, 57.9)',
                                                                         'ge15_pct': '43.1 (39.8, 46.3)',
                                                                         'ge20_pct': '28.6 (25.8, 31.3) - not '
                                                                                     'included in the pre-specified '
                                                                                     'hierarchical testing'},
                                  'additional_studies': {'STEP 2 (Study 3)': {'population': 'Adults with type 2 '
                                                                                            'diabetes and BMI >= 27 '
                                                                                            'kg/m2, HbA1c 7-10%, on '
                                                                                            'diet/exercise alone or '
                                                                                            '1-3 oral antidiabetic '
                                                                                            'drugs. N=807.',
                                                                              'duration_weeks': 68,
                                                                              'arms': [{'dose_mg': 'placebo',
                                                                                        'n': 403,
                                                                                        'weight_change_pct': -3.4,
                                                                                        'ge5_pct': 30.2,
                                                                                        'ge10_pct': 10.2,
                                                                                        'ge15_pct': 4.3,
                                                                                        'ge20_pct': 2.3},
                                                                                       {'dose_mg': 2.4,
                                                                                        'n': 404,
                                                                                        'weight_change_pct': -9.6,
                                                                                        'ge5_pct': 67.4,
                                                                                        'ge10_pct': 44.5,
                                                                                        'ge15_pct': 25.1,
                                                                                        'ge20_pct': 12.8}],
                                                                              'diff_weight_change_pct': '-6.2 (-7.3, '
                                                                                                        '-5.2)'},
                                                         'STEP 3 (Study 4)': {'population': 'Adults with '
                                                                                            'obesity/overweight (no '
                                                                                            'diabetes) treated with '
                                                                                            'an initial 8-week '
                                                                                            'low-calorie diet then '
                                                                                            '60 weeks '
                                                                                            'reduced-calorie diet + '
                                                                                            'exercise. N=611.',
                                                                              'duration_weeks': 68,
                                                                              'arms': [{'dose_mg': 'placebo',
                                                                                        'n': 204,
                                                                                        'weight_change_pct': -5.7,
                                                                                        'ge5_pct': 47.8,
                                                                                        'ge10_pct': 27.1,
                                                                                        'ge15_pct': 13.2,
                                                                                        'ge20_pct': 3.5},
                                                                                       {'dose_mg': 2.4,
                                                                                        'n': 407,
                                                                                        'weight_change_pct': -16.0,
                                                                                        'ge5_pct': 84.8,
                                                                                        'ge10_pct': 73.0,
                                                                                        'ge15_pct': 53.4,
                                                                                        'ge20_pct': 33.9}],
                                                                              'diff_weight_change_pct': '-10.3 '
                                                                                                        '(-11.8, '
                                                                                                        '-8.7)'},
                                                         'STEP 4 (Study 5, placebo withdrawal)': {'population': '902 '
                                                                                                                'enrolled '
                                                                                                                'and '
                                                                                                                'all '
                                                                                                                'treated '
                                                                                                                'with '
                                                                                                                'WEGOVY '
                                                                                                                'for '
                                                                                                                'a '
                                                                                                                '20-week '
                                                                                                                'run-in; '
                                                                                                                '803 '
                                                                                                                'who '
                                                                                                                'reached '
                                                                                                                '2.4 '
                                                                                                                'mg '
                                                                                                                'were '
                                                                                                                'randomised '
                                                                                                                '2:1 '
                                                                                                                'to '
                                                                                                                'continue '
                                                                                                                'or '
                                                                                                                'switch '
                                                                                                                'to '
                                                                                                                'placebo. '
                                                                                                                'Primary '
                                                                                                                'endpoint '
                                                                                                                '= % '
                                                                                                                'change '
                                                                                                                'from '
                                                                                                                'Week '
                                                                                                                '20 '
                                                                                                                'to '
                                                                                                                'Week '
                                                                                                                '68.',
                                                                                                  'duration_weeks': 68,
                                                                                                  'arms': [{'dose_mg': 'placebo '
                                                                                                                       '(withdrawal)',
                                                                                                            'n': 268},
                                                                                                           {'dose_mg': 2.4,
                                                                                                            'n': 535}],
                                                                                                  'diff': '-14.8 '
                                                                                                          '(-16, '
                                                                                                          '-13.5)',
                                                                                                  'note': 'Label '
                                                                                                          'caution: '
                                                                                                          "'Because "
                                                                                                          'patients '
                                                                                                          'who '
                                                                                                          'discontinued '
                                                                                                          'WEGOVY '
                                                                                                          'injection '
                                                                                                          'during '
                                                                                                          'titration '
                                                                                                          'and those '
                                                                                                          'who did '
                                                                                                          'not reach '
                                                                                                          'the 2.4 '
                                                                                                          'mg weekly '
                                                                                                          'dose were '
                                                                                                          'not '
                                                                                                          'eligible '
                                                                                                          'for the '
                                                                                                          'randomized-treatment '
                                                                                                          'period, '
                                                                                                          'the '
                                                                                                          'results '
                                                                                                          'may not '
                                                                                                          'reflect '
                                                                                                          'the '
                                                                                                          'experience '
                                                                                                          'of '
                                                                                                          'patients '
                                                                                                          'in the '
                                                                                                          'general '
                                                                                                          'population '
                                                                                                          'who are '
                                                                                                          'first '
                                                                                                          'starting '
                                                                                                          'WEGOVY '
                                                                                                          "injection.'"},
                                                         'East-Asian trial (Study 6)': {'population': '401 '
                                                                                                      'East-Asian '
                                                                                                      'patients '
                                                                                                      '(Japan, South '
                                                                                                      'Korea) with '
                                                                                                      'BMI >= 35 '
                                                                                                      'plus >=1 '
                                                                                                      'comorbidity, '
                                                                                                      'or BMI '
                                                                                                      '27-34.9 plus '
                                                                                                      '>=2 '
                                                                                                      'comorbidities; '
                                                                                                      '24.7% had '
                                                                                                      'type 2 '
                                                                                                      'diabetes.',
                                                                                        'duration_weeks': 68,
                                                                                        'arms': [{'dose_mg': 'placebo',
                                                                                                  'n': 101,
                                                                                                  'weight_change_pct': -2.1,
                                                                                                  'ge5_pct': 19.4,
                                                                                                  'ge10_pct': 4.5,
                                                                                                  'ge15_pct': 2.6},
                                                                                                 {'dose_mg': 1.7,
                                                                                                  'n': 101,
                                                                                                  'weight_change_pct': -9.6,
                                                                                                  'ge5_pct': 72.8,
                                                                                                  'ge10_pct': 39.1,
                                                                                                  'ge15_pct': 20.8},
                                                                                                 {'dose_mg': 2.4,
                                                                                                  'n': 199,
                                                                                                  'weight_change_pct': -13.2,
                                                                                                  'ge5_pct': 84.0,
                                                                                                  'ge10_pct': 59.9,
                                                                                                  'ge15_pct': 38.2}]},
                                                         'WEGOVY 7.2 mg injection - Study 8 (obesity, no diabetes)': {'label_source': 'current '
                                                                                                                                      'DailyMed '
                                                                                                                                      'SPL '
                                                                                                                                      '(2026-06-30) '
                                                                                                                                      'only; '
                                                                                                                                      'NOT '
                                                                                                                                      'in '
                                                                                                                                      'the '
                                                                                                                                      '02/2026 '
                                                                                                                                      'accessdata '
                                                                                                                                      'PDF',
                                                                                                                      'population': '1,407 '
                                                                                                                                    'adults '
                                                                                                                                    'with '
                                                                                                                                    'obesity '
                                                                                                                                    '(BMI '
                                                                                                                                    '>= '
                                                                                                                                    '30 '
                                                                                                                                    'kg/m2); '
                                                                                                                                    'type '
                                                                                                                                    '2 '
                                                                                                                                    'diabetes '
                                                                                                                                    'excluded. '
                                                                                                                                    'Randomised '
                                                                                                                                    '5:1:1 '
                                                                                                                                    'to '
                                                                                                                                    '7.2 '
                                                                                                                                    'mg '
                                                                                                                                    '/ '
                                                                                                                                    '2.4 '
                                                                                                                                    'mg '
                                                                                                                                    '/ '
                                                                                                                                    'placebo.',
                                                                                                                      'duration_weeks': 72,
                                                                                                                      'arms': [{'dose_mg': 'placebo',
                                                                                                                                'n': 201,
                                                                                                                                'weight_change_pct': -3.9,
                                                                                                                                'ge5_pct': 38.3,
                                                                                                                                'ge10_pct': 20.3,
                                                                                                                                'ge15_pct': 7.5,
                                                                                                                                'ge20_pct': 2.8},
                                                                                                                               {'dose_mg': 2.4,
                                                                                                                                'n': 201,
                                                                                                                                'weight_change_pct': -15.5,
                                                                                                                                'ge5_pct': 86.6,
                                                                                                                                'ge10_pct': 72.0,
                                                                                                                                'ge15_pct': 52.3,
                                                                                                                                'ge20_pct': 32.3},
                                                                                                                               {'dose_mg': 7.2,
                                                                                                                                'n': 1005,
                                                                                                                                'weight_change_pct': -18.8,
                                                                                                                                'ge5_pct': 88.7,
                                                                                                                                'ge10_pct': 79.8,
                                                                                                                                'ge15_pct': 63.8,
                                                                                                                                'ge20_pct': 45.5}],
                                                                                                                      'diff_7_2_vs_placebo_pct': '-14.9 '
                                                                                                                                                 '[-16.2; '
                                                                                                                                                 '-13.6]',
                                                                                                                      'diff_7_2_vs_2_4_pct': '-3.3 '
                                                                                                                                             '[-4.9; '
                                                                                                                                             '-1.6]'},
                                                         'WEGOVY 7.2 mg injection - Study 9 (obesity + type 2 diabetes)': {'population': '512 '
                                                                                                                                         'adults '
                                                                                                                                         'with '
                                                                                                                                         'obesity '
                                                                                                                                         'and '
                                                                                                                                         'type '
                                                                                                                                         '2 '
                                                                                                                                         'diabetes, '
                                                                                                                                         'HbA1c '
                                                                                                                                         '7-10%, '
                                                                                                                                         'on '
                                                                                                                                         'diet/exercise '
                                                                                                                                         'alone '
                                                                                                                                         'or '
                                                                                                                                         '1-3 '
                                                                                                                                         'oral '
                                                                                                                                         'antidiabetic '
                                                                                                                                         'drugs. '
                                                                                                                                         'Randomised '
                                                                                                                                         '3:1:1 '
                                                                                                                                         'to '
                                                                                                                                         '7.2 '
                                                                                                                                         'mg '
                                                                                                                                         '/ '
                                                                                                                                         '2.4 '
                                                                                                                                         'mg '
                                                                                                                                         '/ '
                                                                                                                                         'placebo.',
                                                                                                                           'duration_weeks': 72,
                                                                                                                           'arms': [{'dose_mg': 'placebo',
                                                                                                                                     'n': 102,
                                                                                                                                     'weight_change_pct': -3.8,
                                                                                                                                     'ge5_pct': 34.0,
                                                                                                                                     'ge10_pct': 11.2,
                                                                                                                                     'ge15_pct': 6.9,
                                                                                                                                     'ge20_pct': 2.0},
                                                                                                                                    {'dose_mg': 2.4,
                                                                                                                                     'n': 103,
                                                                                                                                     'weight_change_pct': -10.4,
                                                                                                                                     'ge5_pct': 74.0,
                                                                                                                                     'ge10_pct': 49.5,
                                                                                                                                     'ge15_pct': 27.0,
                                                                                                                                     'ge20_pct': 13.5},
                                                                                                                                    {'dose_mg': 7.2,
                                                                                                                                     'n': 307,
                                                                                                                                     'weight_change_pct': -13.2,
                                                                                                                                     'ge5_pct': 83.7,
                                                                                                                                     'ge10_pct': 60.7,
                                                                                                                                     'ge15_pct': 39.8,
                                                                                                                                     'ge20_pct': 20.6}],
                                                                                                                           'diff_7_2_vs_placebo_pct': '-9.4 '
                                                                                                                                                      '[-11.0; '
                                                                                                                                                      '-7.8]'},
                                                         'WEGOVY 25 mg oral tablet - Study 7': {'label_source': 'current '
                                                                                                                'DailyMed '
                                                                                                                'SPL '
                                                                                                                '(2026-06-30)',
                                                                                                'population': '307 '
                                                                                                              'adults '
                                                                                                              'with '
                                                                                                              'obesity/overweight '
                                                                                                              'with '
                                                                                                              '>=1 '
                                                                                                              'weight-related '
                                                                                                              'comorbidity; '
                                                                                                              'type '
                                                                                                              '2 '
                                                                                                              'diabetes '
                                                                                                              'excluded. '
                                                                                                              'Randomised '
                                                                                                              '2:1.',
                                                                                                'duration_weeks': 64,
                                                                                                'arms': [{'dose_mg': 'placebo',
                                                                                                          'n': 102,
                                                                                                          'weight_change_pct': -2.4,
                                                                                                          'ge5_pct': 31.3,
                                                                                                          'ge10_pct': 14.4,
                                                                                                          'ge15_pct': 5.5,
                                                                                                          'ge20_pct': 3.1},
                                                                                                         {'dose_mg': '25 '
                                                                                                                     'mg '
                                                                                                                     'oral '
                                                                                                                     'tablet',
                                                                                                          'n': 205,
                                                                                                          'weight_change_pct': -13.6,
                                                                                                          'ge5_pct': 76.3,
                                                                                                          'ge10_pct': 59.8,
                                                                                                          'ge15_pct': 47.0,
                                                                                                          'ge20_pct': 27.9}],
                                                                                                'diff_weight_change_pct': '-11.2 '
                                                                                                                          '[-13.6; '
                                                                                                                          '-8.8]'}},
                                  'cardiovascular_outcomes': {'trial': "SELECT (labelled 'Study 1', NCT03574597): "
                                                                       '17,604 adults >=45 y with BMI >=27 and '
                                                                       'established CV disease (prior MI, prior '
                                                                       'stroke or PAD); type 1 and type 2 diabetes '
                                                                       'excluded. Median follow-up 41.8 months.',
                                                              'primary_mace': '701 (8%) placebo vs 569 (6.5%) '
                                                                              'WEGOVY; HR 0.80 (95% CI 0.72, 0.90), '
                                                                              'p<0.001 one-sided',
                                                              'cv_death': '262 (3%) vs 223 (2.5%); HR 0.85 (0.71, '
                                                                          '1.01) - superiority NOT confirmed in the '
                                                                          'testing hierarchy',
                                                              'all_cause_death': '458 (5.2%) vs 375 (4.3%); HR 0.81 '
                                                                                 '(0.71, 0.93) - confirmatory '
                                                                                 'secondary endpoint, not '
                                                                                 'statistically significant per the '
                                                                                 'prespecified hierarchy',
                                                              'fatal_or_nonfatal_mi': '334 (3.8%) vs 243 (2.8%); HR '
                                                                                      '0.72 (0.61, 0.85)',
                                                              'fatal_or_nonfatal_stroke': '178 (2%) vs 160 (1.8%); '
                                                                                          'HR 0.89 (0.72, 1.11)',
                                                              'hf_hospitalisation_or_urgent_hf_visit': '122 (1.4%) '
                                                                                                       'vs 97 '
                                                                                                       '(1.1%); HR '
                                                                                                       '0.79 (0.6, '
                                                                                                       '1.03) - '
                                                                                                       'effect on '
                                                                                                       'heart '
                                                                                                       'failure not '
                                                                                                       'established',
                                                              'body_weight_at_week_104': 'placebo 96.8 kg baseline, '
                                                                                         '-0.93 kg; WEGOVY 96.5 kg '
                                                                                         'baseline, -9.43 kg; '
                                                                                         'difference -8.53 kg (these '
                                                                                         'parameters were not in the '
                                                                                         'prespecified hierarchical '
                                                                                         'testing)'},
                                  'sources': [{'claim': 'STEP 1 (Study 2) population and duration',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                               'section': 'PDF p.28 (section 14.2)',
                                               'quote': 'Study 2 was a 68-week trial that enrolled 1,961 patients '
                                                        'with obesity (BMI greater than or equal to 30 kg/m2) or '
                                                        'with overweight (BMI 27 to 29.9 kg/m2) and at least one '
                                                        'weight-related comorbid condition, such as treated or '
                                                        'untreated dyslipidemia or hypertension; patients with type '
                                                        '2 diabetes mellitus were excluded.'},
                                              {'claim': 'STEP 1/2/3 body-weight results at Week 68',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                               'section': 'PDF p.29 (Table 7)',
                                               'quote': 'Table 7. Changes in Body Weight at Week 68 in Studies 2, 3, '
                                                        'and 4 with WEGOVY Injection ... Intention-to-Treat ... '
                                                        'Study 2 (Obesity or Overweight with Comorbidity): PLACEBO '
                                                        'N=655, WEGOVY Injection N=1,306 ... % change from baseline '
                                                        '(LSMean) -2.4 | -14.9 ... % of Patients losing greater than '
                                                        'or equal to 5% body weight 31.1 | 83.5 ... % of Patients '
                                                        'losing greater than or equal to 15% body weight 4.8 | 47.9 '
                                                        '... % of Patients losing greater than or equal to 20% body '
                                                        'weight 1.7 | 30.2'},
                                              {'claim': 'Estimand / missing-data handling for the STEP weight tables',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                               'section': 'PDF p.29-30 (Table 7 footnote 1)',
                                               'quote': 'The intent-to-treat population includes all randomized '
                                                        'patients. In Study 2, at Week 68, the body weight was '
                                                        'missing for 7.2% and 11.9% of patients randomized to WEGOVY '
                                                        'injection and placebo, respectively. ... Missing data were '
                                                        'imputed from retrieved subjects of the same randomized '
                                                        'treatment arm (RD-MI).'},
                                              {'claim': 'SELECT primary MACE result and components',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                               'section': 'PDF p.26 (section 14.1, Table 6)',
                                               'quote': 'WEGOVY injection significantly reduced the risk for first '
                                                        'occurrence of MACE. The estimated hazard ratio (95% CI) was '
                                                        '0.80 (0.72, 0.90) ... Composite of CV death, non-fatal '
                                                        'myocardial infarction, or non-fatal stroke: 701 (8%) '
                                                        'placebo vs 569 (6.5%) WEGOVY, Hazard Ratio 0.8 (0.72; '
                                                        '0.9)*,2'},
                                              {'claim': 'WEGOVY 7.2 mg and 2.4 mg results at Week 72 (Studies 8 and '
                                                        '9)',
                                               'url': 'https://dailymed.nlm.nih.gov/dailymed/downloadpdffile.cfm?setId=ee06186f-2aa3-4990-a760-757579d8f77b',
                                               'section': 'PDF p.45 (Table 12)',
                                               'quote': 'Table 12.Changes in Body Weight at Week 72 in Studies 8 and '
                                                        '9 with WEGOVY 7.2 mg Injection ... Study 8 (Patients with '
                                                        'Obesity) PLACEBO N=201 | WEGOVY 2.4 mg Injection N=201 | '
                                                        'WEGOVY 7.2 mg Injection N=1005 ... % change from baseline '
                                                        '(LSMean)a -3.9 | -15.5 | -18.8 ... Study 9 (Type 2 Diabetes '
                                                        'with Obesity) PLACEBO N=102 | WEGOVY 2.4 mg Injection N=103 '
                                                        '| WEGOVY 7.2 mg Injection N=307 ... -3.8 | -10.4** | -13.2'},
                                              {'claim': 'WEGOVY 25 mg oral tablet results at Week 64 (Study 7)',
                                               'url': 'https://dailymed.nlm.nih.gov/dailymed/downloadpdffile.cfm?setId=ee06186f-2aa3-4990-a760-757579d8f77b',
                                               'section': 'PDF p.44 (Table 11)',
                                               'quote': 'Table 11. Changes in Body Weight at Week 64 in Study 7 with '
                                                        'WEGOVY 25 mg Tablets ... PLACEBO N = 102 | WEGOVY 25 mg '
                                                        'Tablet N = 205 ... % change from baseline (LSMean)3 -2.4 | '
                                                        '-13.6 ... % of Patients losing greater than or equal to 5% '
                                                        'body weight3 31.3 | 76.3 ... % of Patients losing greater '
                                                        'than or equal to 10% body weight 14.4 | 59.8 ... % of '
                                                        'Patients losing greater than or equal to 15% body weight3 '
                                                        '5.5 | 47 ... % of Patients losing greater than or equal to '
                                                        '20% body weight3 3.1 | 27.9'}],
                                  'verified': True}],
                 'gi_side_effects': {'trial': 'Pool of 3 randomised, double-blind, placebo-controlled trials, 2,116 '
                                              'adults with obesity/overweight treated with WEGOVY 2.4 mg injection '
                                              'for up to 68 weeks',
                                     'arms': [{'dose_mg': 'placebo',
                                               'n': 1261,
                                               'nausea_pct': 16,
                                               'vomiting_pct': 6,
                                               'diarrhoea_pct': 16,
                                               'constipation_pct': 11,
                                               'discontinuation_due_to_ae_pct': 3.2},
                                              {'dose_mg': 2.4,
                                               'n': 2116,
                                               'nausea_pct': 44,
                                               'vomiting_pct': 24,
                                               'diarrhoea_pct': 30,
                                               'constipation_pct': 24,
                                               'discontinuation_due_to_ae_pct': 6.8}],
                                     'leading_discontinuation_reactions': 'nausea 1.8% vs 0.2%; vomiting 1.2% vs 0%; '
                                                                          'diarrhea 0.7% vs 0.1% (WEGOVY vs placebo)',
                                     'other_reactions_of_note': None,
                                     'sources': [{'claim': 'Wegovy 2.4 mg adverse reactions >=2% and discontinuation '
                                                           'due to adverse reactions',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                                  'section': 'PDF p.10 (section 6.1, Table 3)',
                                                  'quote': 'In these clinical trials, 6.8% of patients treated with '
                                                           '2.4 mg WEGOVY and 3.2% of patients treated with placebo '
                                                           'permanently discontinued treatment as a result of '
                                                           'adverse reactions. ... Nausea 16 | 44; Diarrhea 16 | 30; '
                                                           'Vomiting 6 | 24; Constipation 11 | 24 (Placebo N=1,261 | '
                                                           'WEGOVY Injection (2.4 mg once weekly) N=2,116)'},
                                                 {'claim': 'Wegovy SELECT discontinuation due to adverse event',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf',
                                                  'section': 'PDF p.11 (section 6.1)',
                                                  'quote': 'Sixteen percent (16%) of WEGOVY injection-treated '
                                                           'patients and 8% of placebo-treated patients, '
                                                           'respectively, discontinued study drug due to an adverse '
                                                           'event.'},
                                                 {'claim': 'Wegovy 7.2 mg adverse reactions',
                                                  'url': 'https://dailymed.nlm.nih.gov/dailymed/downloadpdffile.cfm?setId=ee06186f-2aa3-4990-a760-757579d8f77b',
                                                  'section': 'PDF p.16 (Table 4)',
                                                  'quote': 'Table 4. Adverse Reactions (2% and Greater Than WEGOVY '
                                                           '2.4 mg and Placebo) in WEGOVY 7.2 mg Injection-treated '
                                                           'Adults with Obesity for Weight Reduction ... Placebo '
                                                           'N=303 % | WEGOVY Injection (2.4 mg Once Weekly) N=304 % '
                                                           '| WEGOVY Injection (7.2 mg Once Weekly) N=1,311 %: '
                                                           'Nausea 13 | 35 | 39; Vomiting 6 | 16 | 22; Dysesthesia 0 '
                                                           '| 6 | 22; Constipation 8 | 19 | 20'}],
                                     'verified': True},
                 'body_composition': {'lean_mass_pct_of_weight_loss': None,
                                      'source': None,
                                      'notes': 'NOT VERIFIED. The Wegovy label states only qualitatively, in section '
                                               '12.2 Pharmacodynamics (PDF p.21 of the 02/2026 accessdata label): '
                                               "'Semaglutide lowers body weight with greater fat mass loss than lean "
                                               "mass loss.' No percentage or proportion is given anywhere in the "
                                               'label. The STEP 1 DXA body-composition analysis that is usually '
                                               'cited exists as a conference abstract (J Endocr Soc 2021;5(Suppl '
                                               "1):A16-A17) and as a review summary (O'Neil PM, Rubino DM. Postgrad "
                                               'Med. 2022;134(sup1):28-36. doi:10.1080/00325481.2022.2150006), which '
                                               "states only that 'reductions in total fat mass were greater with "
                                               "semaglutide versus placebo'. I could not retrieve a full "
                                               'peer-reviewed primary publication reporting the lean-mass percentage '
                                               'for semaglutide 2.4 mg in STEP 1, so this field is left null rather '
                                               'than filled from memory or a secondary summary.'},
                 'notes': 'The 2.4 mg numbers are identical in the 02/2026 FDA-distributed PDF and the newer '
                          "2026-06-30 SPL. Be careful with study numbering: the Wegovy label calls STEP 1 'Study 2', "
                          "STEP 2 'Study 3', STEP 3 'Study 4', STEP 4 'Study 5', and SELECT 'Study 1'. The >=20% "
                          "responder difference for STEP 1 is flagged in the label as 'Not included in the "
                          "pre-specified hierarchical testing' - it is descriptive. Wegovy is now approved in three "
                          'forms (2.4 mg injection, 7.2 mg injection, 25 mg oral tablet); do not mix their numbers.',
                 'confidence': 'high',
                 'label_urls': ['https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/215256s033lbl.pdf '
                                '(WEGOVY, Revised 02/2026 - 2.4 mg data; does NOT contain the 7.2 mg or oral-tablet '
                                'data)',
                                'https://dailymed.nlm.nih.gov/dailymed/downloadpdffile.cfm?setId=ee06186f-2aa3-4990-a760-757579d8f77b '
                                '(WEGOVY, Novo Nordisk SPL published 2026-06-30 - includes 7.2 mg injection and 25 '
                                'mg tablet)']},
 'liraglutide': {'drug_id': 'liraglutide',
                 'brand_names': [],
                 'indications': [{'kind': 'weight',
                                  'trial': "SCALE (labelled 'Study 1'), with Study 2 (type 2 diabetes) and Study 3 "
                                           '(after a diet-induced >=5% weight-loss run-in)',
                                  'population': 'Study 1: 3,731 adults with obesity (BMI >= 30 kg/m2) or overweight '
                                                '(BMI 27-29.9 kg/m2) with at least one weight-related comorbid '
                                                'condition (treated or untreated dyslipidemia or hypertension); '
                                                'patients with type 2 diabetes were excluded. Randomised 2:1. Mean '
                                                'baseline weight 106.3 kg, mean BMI 38.3 kg/m2. A subset with '
                                                'abnormal glucose measurements at randomisation (2,254 of 3,731) '
                                                'continued to 160 weeks.',
                                  'duration_weeks': 56,
                                  'estimand': "Not a strict ITT estimand. The label states: 'Includes all randomized "
                                              'subjects who had a baseline body weight measurement. All available '
                                              'body weight data during the 56 week treatment period are included in '
                                              'the analysis. In Studies 1 and 2 missing values for Week 56 were '
                                              'handled using multiple imputations analysis. In Study 3 missing '
                                              "values for Week 56 were handled using weighted regression analysis.' "
                                              'Anthropometry tables use last-observation-carried-forward while on '
                                              'study drug. Weight changes are LS means.',
                                  'arms': [{'dose_mg': 'placebo',
                                            'n': 1244,
                                            'weight_change_pct': -3.0,
                                            'ge5_pct': 34.4,
                                            'ge10_pct': 15.4,
                                            'ge15_pct': None,
                                            'ge20_pct': None},
                                           {'dose_mg': 3.0,
                                            'n': 2487,
                                            'weight_change_pct': -7.4,
                                            'ge5_pct': 62.3,
                                            'ge10_pct': 33.9,
                                            'ge15_pct': None,
                                            'ge20_pct': None},
                                           {'dose_mg': 'placebo',
                                            'n': 212,
                                            'weight_change_pct': -1.7,
                                            'ge5_pct': 16.4,
                                            'ge10_pct': 5.5,
                                            'ge15_pct': None,
                                            'ge20_pct': None},
                                           {'dose_mg': 3.0,
                                            'n': 423,
                                            'weight_change_pct': -5.4,
                                            'ge5_pct': 49.0,
                                            'ge10_pct': 22.4,
                                            'ge15_pct': None,
                                            'ge20_pct': None},
                                           {'dose_mg': 'placebo',
                                            'n': 210,
                                            'weight_change_pct': 0.3,
                                            'ge5_pct': 21.7,
                                            'ge10_pct': 6.9,
                                            'ge15_pct': None,
                                            'ge20_pct': None},
                                           {'dose_mg': 3.0,
                                            'n': 212,
                                            'weight_change_pct': -4.9,
                                            'ge5_pct': 44.2,
                                            'ge10_pct': 25.4,
                                            'ge15_pct': None,
                                            'ge20_pct': None}],
                                  'between_arm_differences_vs_placebo': {'Study 1': {'weight_change_pct': '-4.5 '
                                                                                                          '(-5.2, '
                                                                                                          '-3.8)',
                                                                                     'ge5_pct': '27.9 (23.9, 31.9)',
                                                                                     'ge10_pct': '18.5 (15.2, 21.7)'},
                                                                         'Study 2': {'weight_change_pct': '-3.7 '
                                                                                                          '(-4.7, '
                                                                                                          '-2.7)',
                                                                                     'ge5_pct': '32.6 (25.1, 40.1)',
                                                                                     'ge10_pct': '16.9 (11.7, 22.1)'},
                                                                         'Study 3': {'weight_change_pct': '-5.2 '
                                                                                                          '(-6.8, '
                                                                                                          '-3.5)',
                                                                                     'ge5_pct': '22.6 (13.9, 31.3)',
                                                                                     'ge10_pct': '18.5 (11.7, '
                                                                                                 '25.3)'}},
                                  'additional_studies': None,
                                  'cardiovascular_outcomes': None,
                                  'sources': [{'claim': 'SCALE (Study 1) population, design and duration',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                               'section': 'PDF p.19-20 (section 14.1)',
                                               'quote': 'Study 1 enrolled 3,731 patients with obesity (BMI greater '
                                                        'than or equal to 30 kg/m2) or with overweight (BMI 27-29.9 '
                                                        'kg/m2) and at least one weight-related comorbid condition '
                                                        'such as treated or untreated dyslipidemia or hypertension; '
                                                        'patients with type 2 diabetes mellitus were excluded. '
                                                        'Patients were randomized in a 2:1 ratio to either SAXENDA '
                                                        'or placebo. ... All patients were treated for up to 56 '
                                                        'weeks.'},
                                              {'claim': 'SCALE Study 1, 2 and 3 body-weight results at Week 56',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                               'section': 'PDF p.20-21 (Table 4)',
                                               'quote': 'Table 4. Changes in Weight at Week 56 for Studies 1, 2, and '
                                                        '3 ... Study 1 (Obesity or overweight with comorbidity) '
                                                        'SAXENDA N=2487 Placebo N=1244 ... Percent change from '
                                                        'baseline (LSMean) -7.4 | -3 ... % of Patients losing '
                                                        'greater than or equal to 5% body weight 62.3% | 34.4% ... % '
                                                        'of Patients losing greater than 10% body weight 33.9% | '
                                                        '15.4%'},
                                              {'claim': 'Missing-data handling / estimand footnote',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                               'section': 'PDF p.21 (Table 4 footnote)',
                                               'quote': 'Includes all randomized subjects who had a baseline body '
                                                        'weight measurement. All available body weight data during '
                                                        'the 56 week treatment period are included in the analysis. '
                                                        'In Studies 1 and 2 missing values for Week 56 were handled '
                                                        'using multiple imputations analysis. In Study 3 missing '
                                                        'values for Week 56 were handled using weighted regression '
                                                        'analysis.'},
                                              {'claim': '160-week descriptive results',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                               'section': 'PDF p.22 (Table 5)',
                                               'quote': 'The numbers and percentages of patients known to have lost '
                                                        'greater than or equal to 5% body weight at Week 56 and/or '
                                                        'Week 160 in Study 1 (patients with abnormal glucose at '
                                                        'randomization only) are summarized in Table 5 for '
                                                        'descriptive purposes. ... Number (%) of patients known to '
                                                        'lose greater than or equal to 5% body weight at 160 weeks: '
                                                        '424 (28%) SAXENDA vs 102 (14%) placebo'},
                                              {'claim': 'No >=15% or >=20% responder data in the Saxenda label',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                               'section': 'PDF p.20-21 (Table 4)',
                                               'quote': 'For Study 1 and Study 2, the primary efficacy parameters '
                                                        'were mean percent change in body weight and the percentages '
                                                        'of patients achieving greater than or equal to 5% and 10% '
                                                        'weight loss from baseline to Week 56. (no >=15% or >=20% '
                                                        'responder data are reported anywhere in the label)'}],
                                  'verified': True}],
                 'gi_side_effects': {'trial': '5 double-blind, placebo-controlled trials in 3,384 overweight/obese '
                                              'adults treated with SAXENDA (up to 56 weeks in 3 trials, 52 weeks in '
                                              '1, 32 weeks in 1), plus one 56-week paediatric trial',
                                     'arms': [{'dose_mg': 'placebo',
                                               'n': 1941,
                                               'nausea_pct': 13.8,
                                               'vomiting_pct': 3.9,
                                               'diarrhoea_pct': 9.9,
                                               'constipation_pct': 8.5,
                                               'discontinuation_due_to_ae_pct': 4.3},
                                              {'dose_mg': 3.0,
                                               'n': 3384,
                                               'nausea_pct': 39.3,
                                               'vomiting_pct': 15.7,
                                               'diarrhoea_pct': 20.9,
                                               'constipation_pct': 19.4,
                                               'discontinuation_due_to_ae_pct': 9.8}],
                                     'leading_discontinuation_reactions': 'nausea 2.9% vs 0.2%; vomiting 1.7% vs '
                                                                          '<0.1%; diarrhea 1.4% vs 0% (SAXENDA vs '
                                                                          'placebo)',
                                     'other_reactions_of_note': 'Hypoglycaemia in T2DM 12.6% vs 6.6%; injection site '
                                                                'reaction 13.9% vs 10.5%; headache 13.6% vs 12.6%; '
                                                                'dyspepsia 9.6% vs 2.7%; increased lipase 5.3% vs '
                                                                '2.2%.',
                                     'sources': [{'claim': 'Saxenda discontinuation due to adverse reactions',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                                  'section': 'PDF p.8 (section 6.1)',
                                                  'quote': 'In adult clinical trials, 9.8% of patients treated with '
                                                           'SAXENDA and 4.3% of patients treated with placebo '
                                                           'prematurely discontinued treatment as a result of '
                                                           'adverse reactions. The most common adverse reactions '
                                                           'leading to discontinuation were nausea (2.9% versus 0.2% '
                                                           'for SAXENDA and placebo, respectively), vomiting (1.7% '
                                                           'versus less than 0.1%), and diarrhea (1.4% versus 0%).'},
                                                 {'claim': 'Saxenda common adverse reactions >=2%',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                                  'section': 'PDF p.8 (Table 2)',
                                                  'quote': 'Table 2. Adverse Reactions Occurring in >=2% of '
                                                           'SAXENDA-treated Adult Patients and More Frequently than '
                                                           'Placebo ... Placebo N=1941 % | SAXENDA N=3384 %: Nausea '
                                                           '13.8 | 39.3; Diarrhea 9.9 | 20.9; Constipation 8.5 | '
                                                           '19.4; Vomiting 3.9 | 15.7'},
                                                 {'claim': 'Discontinuation of study drug (any cause) and '
                                                           'adverse-reaction discontinuation in the 56-week and '
                                                           '160-week trials',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf',
                                                  'section': 'PDF p.20 (section 14.1)',
                                                  'quote': 'The proportions of patients who discontinued study drug '
                                                           'in the 56-week trials were 27% for the SAXENDA-treated '
                                                           'group and 35% for the placebo-treated group, and in the '
                                                           '160-week trial the proportions of patients who '
                                                           'discontinued were 47% and 55%, respectively. In the '
                                                           '56-week trials, approximately 10% of patients treated '
                                                           'with SAXENDA and 4% of patients treated with placebo '
                                                           'discontinued treatment due to an adverse reaction.'}],
                                     'verified': True},
                 'body_composition': {'lean_mass_pct_of_weight_loss': None,
                                      'source': None,
                                      'notes': 'NOT REPORTED. The Saxenda label contains no lean-mass or fat-mass '
                                               "statement anywhere (searched for 'lean mass', 'fat mass', 'body "
                                               "composition', 'fat-free' - zero hits). I did not locate a "
                                               'peer-reviewed primary SCALE body-composition substudy reporting a '
                                               'lean-mass percentage, so this is left null rather than filled from '
                                               'memory or a review article.'},
                 'notes': "Important asymmetry: Saxenda's label reports ONLY >=5% and >=10% responder rates. There "
                          'is no >=15% or >=20% figure anywhere in the label - do not infer them. The 56-week values '
                          'are the primary endpoint; the 160-week values are descriptive percentages of patients '
                          "'known to lose >=5%', not LS means. Also note that in the 56-week trials all-cause "
                          'discontinuation of study drug was LOWER with SAXENDA (27%) than with placebo (35%), and '
                          'in the 160-week trial 47% vs 55%.',
                 'confidence': 'high',
                 'label_urls': ['https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/206321s025lbl.pdf']},
 'dulaglutide': {'drug_id': 'dulaglutide',
                 'brand_names': [],
                 'indications': [{'kind': 'diabetes',
                                  'trial': 'AWARD programme: monotherapy vs metformin (26 weeks), vs sitagliptin '
                                           'add-on to metformin (52 weeks), dose-ranging 1.5/3/4.5 mg add-on to '
                                           'metformin (36 weeks)',
                                  'population': 'Adults with type 2 diabetes mellitus (see per-row population '
                                                'notes).',
                                  'duration_weeks': '26 / 36 / 52',
                                  'estimand': 'Intent-to-treat; LOCF used to impute missing data; data after onset '
                                              'of rescue therapy treated as missing; LS means adjusted for baseline '
                                              'value and stratification factors. For the dose-ranging trial, missing '
                                              'data were imputed using multiple imputation.',
                                  'arms': [{'dose_mg': 0.75,
                                            'n': 270,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -1.4,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -0.7},
                                           {'dose_mg': 1.5,
                                            'n': 269,
                                            'weight_change_pct': None,
                                            'weight_change_kg': -2.3,
                                            'ge5_pct': None,
                                            'ge10_pct': None,
                                            'ge15_pct': None,
                                            'ge20_pct': None,
                                            'hba1c_change_pct': -0.8},
                                           {'dose_mg': 'metformin 1500-2000 mg',
                                            'n': 268,
                                            'weight_change_kg': -2.2,
                                            'hba1c_change_pct': -0.6},
                                           {'dose_mg': 0.75,
                                            'n': 281,
                                            'weight_change_kg': -2.7,
                                            'hba1c_change_pct': -0.9},
                                           {'dose_mg': 1.5,
                                            'n': 279,
                                            'weight_change_kg': -3.1,
                                            'hba1c_change_pct': -1.1},
                                           {'dose_mg': 'sitagliptin 100 mg',
                                            'n': 273,
                                            'weight_change_kg': -1.5,
                                            'hba1c_change_pct': -0.4},
                                           {'dose_mg': 1.5,
                                            'n': 612,
                                            'weight_change_kg': -3.0,
                                            'hba1c_change_pct': -1.5},
                                           {'dose_mg': 3.0,
                                            'n': 616,
                                            'weight_change_kg': -3.8,
                                            'hba1c_change_pct': -1.6},
                                           {'dose_mg': 4.5,
                                            'n': 614,
                                            'weight_change_kg': -4.6,
                                            'hba1c_change_pct': -1.8}],
                                  'between_arm_differences_vs_placebo': None,
                                  'additional_studies': None,
                                  'cardiovascular_outcomes': {'trial': 'REWIND (NCT01394952): 9,901 adults with type '
                                                                       '2 diabetes and established CV disease '
                                                                       '(31.5%) or multiple CV risk factors (62.8%), '
                                                                       'dulaglutide 1.5 mg vs placebo, median '
                                                                       'follow-up 5.4 years',
                                                              'primary_mace': '663 (13.4%) placebo vs 594 (12.0%) '
                                                                              'TRULICITY; HR 0.88 (95% CI 0.79, '
                                                                              '0.99), p=0.026 for superiority '
                                                                              '(2-sided)',
                                                              'components': {'cv_death': '346 (7.0%) vs 317 (6.4%); '
                                                                                         'HR 0.91 (0.78, 1.06)',
                                                                             'non_fatal_mi': '212 (4.3%) vs 205 '
                                                                                             '(4.1%); HR 0.96 (0.79, '
                                                                                             '1.16)',
                                                                             'non_fatal_stroke': '175 (3.5%) vs 135 '
                                                                                                 '(2.7%); HR 0.76 '
                                                                                                 '(0.61, 0.95)',
                                                                             'fatal_or_nonfatal_mi': '231 (4.7%) vs '
                                                                                                     '223 (4.5%); HR '
                                                                                                     '0.96 (0.79, '
                                                                                                     '1.15)',
                                                                             'fatal_or_nonfatal_stroke': '205 (4.1%) '
                                                                                                         'vs 158 '
                                                                                                         '(3.2%); HR '
                                                                                                         '0.76 '
                                                                                                         '(0.62, '
                                                                                                         '0.94)',
                                                                             'all_cause_death': '592 (12.0%) placebo '
                                                                                                'vs 536 (10.8%) '
                                                                                                'dulaglutide'},
                                                              'note': 'Component results other than the primary '
                                                                      "composite are 'listed descriptively for "
                                                                      'supportive purposes. No statistical '
                                                                      'significance should be inferred since these '
                                                                      "CIs are not adjusted for multiplicity.'"},
                                  'sources': [{'claim': 'Trulicity monotherapy HbA1c and weight at Week 26',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                               'section': 'PDF p.16 (Table 4)',
                                               'quote': 'Table 4: Results at Week 26 in a Trial of TRULICITY as '
                                                        'Monotherapy in Adult Patients with Type 2 Diabetes Mellitus '
                                                        '... Change from baselineb -0.7 (0.75 mg) | -0.8 (1.5 mg) | '
                                                        '-0.6 (metformin) ... Body Weight (kg) (Mean) Baseline 91.8 '
                                                        '| 92.7 | 92.4 ... Change from baselineb -1.4 | -2.3 | -2.2'},
                                              {'claim': 'Trulicity vs sitagliptin HbA1c and weight at Week 52',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                               'section': 'PDF p.17 (Table 5)',
                                               'quote': 'Table 5: Results at Week 52 of TRULICITY Compared to '
                                                        'Sitagliptin used as Add-On to Metformin ... Change from '
                                                        'baselineb -0.9 | -1.1 | -0.4 ... Body Weight (kg) (Mean) '
                                                        'Change from baselineb -2.7 | -3.1 | -1.5'},
                                              {'claim': 'Trulicity 3 mg and 4.5 mg dose-ranging results at Week 36',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                               'section': 'PDF p.18 (Table 6)',
                                               'quote': 'Table 6. Results at Week 36 of TRULICITY 1.5 mg Compared to '
                                                        '3 mg and 4.5 mg as Add-On to Metformin ... Change from '
                                                        'baseline b -1.5 | -1.6 | -1.8 ... Body Weight (kg) (Mean) '
                                                        'Change from baselineb -3.0 | -3.8 | -4.6'},
                                              {'claim': 'REWIND primary MACE result',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                               'section': 'PDF p.29 (section 14.5, Table 14)',
                                               'quote': 'TRULICITY significantly reduced the risk of first '
                                                        'occurrence of primary composite endpoint of CV death, '
                                                        'non-fatal MI, or non-fatal stroke (HR: 0.88, 95% CI 0.79, '
                                                        '0.99). ... Composite of non-fatal myocardial infarction, '
                                                        'non-fatal stroke, cardiovascular death (MACE)d 594 (12.0%) '
                                                        'TRULICITY vs 663 (13.4%) Placebo, Hazard Ratio (95% CI)b '
                                                        '0.88 (0.79, 0.99)c ... c p=0.026 for superiority '
                                                        '(2-sided).'},
                                              {'claim': 'No percent weight change or responder rates for Trulicity',
                                               'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                               'section': 'PDF p.16-18 (section 14.2-14.3)',
                                               'quote': 'Body Weight (kg) (Mean) Baseline ... Change from baselineb '
                                                        '(all body-weight entries in the Trulicity label are '
                                                        'absolute kg changes; there is no percent weight change and '
                                                        'no proportion of patients achieving >=5%, >=10%, >=15% or '
                                                        '>=20% weight loss anywhere in the label)'}],
                                  'verified': True}],
                 'gi_side_effects': {'trial': 'Pool of adult placebo-controlled trials for the 0.75 mg and 1.5 mg '
                                              'doses (1,670 adults exposed, mean exposure 23.8 weeks); separate '
                                              '36-week dose-ranging table for 3 mg and 4.5 mg',
                                     'arms': [{'dose_mg': 'placebo',
                                               'n': 568,
                                               'nausea_pct': 5.3,
                                               'vomiting_pct': 2.3,
                                               'diarrhoea_pct': 6.7,
                                               'constipation_pct': 0.7,
                                               'discontinuation_due_to_ae_pct': None,
                                               'gi_ae_discontinuation_pct': 0.2,
                                               'any_gi_ae_pct': 21},
                                              {'dose_mg': 0.75,
                                               'n': 836,
                                               'nausea_pct': 12.4,
                                               'vomiting_pct': 6.0,
                                               'diarrhoea_pct': 8.9,
                                               'constipation_pct': 3.9,
                                               'discontinuation_due_to_ae_pct': None,
                                               'gi_ae_discontinuation_pct': 1.3,
                                               'any_gi_ae_pct': 32},
                                              {'dose_mg': 1.5,
                                               'n': 834,
                                               'nausea_pct': 21.1,
                                               'vomiting_pct': 12.7,
                                               'diarrhoea_pct': 12.6,
                                               'constipation_pct': 3.7,
                                               'discontinuation_due_to_ae_pct': None,
                                               'gi_ae_discontinuation_pct': 3.5,
                                               'any_gi_ae_pct': 41},
                                              {'dose_mg': 3.0,
                                               'n': 616,
                                               'nausea_pct': 15.6,
                                               'vomiting_pct': 8.3,
                                               'diarrhoea_pct': 11.4,
                                               'constipation_pct': None,
                                               'discontinuation_due_to_ae_pct': None},
                                              {'dose_mg': 4.5,
                                               'n': 614,
                                               'nausea_pct': 16.4,
                                               'vomiting_pct': 9.3,
                                               'diarrhoea_pct': 10.7,
                                               'constipation_pct': None,
                                               'discontinuation_due_to_ae_pct': None}],
                                     'leading_discontinuation_reactions': None,
                                     'other_reactions_of_note': None,
                                     'sources': [{'claim': 'Trulicity common adverse reactions >=5% (0.75 and 1.5 '
                                                           'mg)',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                                  'section': 'PDF p.6 (Table 1)',
                                                  'quote': 'Table 1: Adverse Reactions in Pool of Placebo-Controlled '
                                                           'Trials That Occurred in >=5% of TRULICITY-Treated Adult '
                                                           'Patients with Type 2 Diabetes Mellitus ... Placebo '
                                                           '(N=568) | TRULICITY 0.75 mg (N=836) | TRULICITY 1.5 mg '
                                                           '(N=834): Nausea 5.3 | 12.4 | 21.1; Diarrheaa 6.7 | 8.9 | '
                                                           '12.6; Vomitingb 2.3 | 6.0 | 12.7'},
                                                 {'claim': 'Trulicity GI adverse reactions and GI discontinuations '
                                                           'incl. constipation',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                                  'section': 'PDF p.7 (section 6.1)',
                                                  'quote': 'In the pool of placebo-controlled trials, '
                                                           'gastrointestinal (GI) adverse reactions occurred more '
                                                           'frequently among patients who received TRULICITY '
                                                           'compared to patients who received placebo (placebo 21%, '
                                                           '0.75 mg 32%, 1.5 mg 41%). A higher percentage of '
                                                           'patients who received TRULICITY 0.75 mg (1.3%) and '
                                                           'TRULICITY 1.5 mg (3.5%) discontinued treatment due to GI '
                                                           'adverse reactions than patients who received placebo '
                                                           '(0.2%). ... constipation (0.7%, 3.9%, 3.7%)'},
                                                 {'claim': 'Trulicity 3 mg and 4.5 mg adverse reactions',
                                                  'url': 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf',
                                                  'section': 'PDF p.7 (Table 2)',
                                                  'quote': 'Table 2: Adverse Reactions That Occurred in >=5% of '
                                                           'TRULICITY-treated Adult Patients with Type 2 Diabetes '
                                                           'Mellitus in a Clinical Trial through 36 Weeks ... '
                                                           'TRULICITY 1.5 mg (N=612) | TRULICITY 3 mg (N=616) | '
                                                           'TRULICITY 4.5 mg (N=614): Nausea 13.4 | 15.6 | 16.4; '
                                                           'Diarrhea 7.0 | 11.4 | 10.7; Vomiting 5.6 | 8.3 | 9.3'}],
                                     'verified': True},
                 'body_composition': {'lean_mass_pct_of_weight_loss': None,
                                      'source': None,
                                      'notes': 'NOT REPORTED. The Trulicity label contains no body-composition '
                                               "statement (no hits for 'lean mass', 'fat mass', 'body composition', "
                                               "'fat-free'). Trulicity is not a weight-management agent, and no "
                                               'pivotal registration trial substudy with a lean-mass percentage was '
                                               'verified, so this is left null.'},
                 'notes': 'Trulicity is included in this dataset mainly as the active comparator in SURPASS-CVOT and '
                          'because it is a GLP-1 RA, but it has NO weight-management indication and its weight '
                          'effects (-2 to -4.6 kg) are much smaller than the dedicated weight-management agents. Do '
                          "not present Trulicity weight numbers as 'weight loss efficacy' alongside Zepbound/Wegovy "
                          'numbers - the populations (type 2 diabetes), endpoints (HbA1c primary) and durations '
                          'differ, and percent weight change is not reported at all.',
                 'confidence': 'high',
                 'label_urls': ['https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf']}}
