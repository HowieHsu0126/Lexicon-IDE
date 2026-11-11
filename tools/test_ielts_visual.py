import copy
import json
from pathlib import Path
import unittest

import ielts_contract as contract
import ielts_visual as visual
from ielts_candidate import compile_candidate
from ielts_json_schema import generation_schema, review_schema
from ielts_review import validate_review
from test_ielts_review import review_fixture


def visual_fixture():
    request, package, review = review_fixture()
    text = ('At the Riverside library, weekly visits rose from 1,200 in 2024 to 1,650 in 2025. '
            'At the Hill library, weekly visits rose from 900 in 2024 to 1,100 in 2025.')
    start = request['paragraphs'][-1]['end'] + 2
    p = {'paragraph_id': 'visual-p1', 'start': start, 'end': start + len(text), 'text': text}
    request['paragraphs'].append(p)
    request['writing_task1_request'] = {'task_id': 'w1', 'policy': 'source_numeric_visual_v1'}
    def ref(quote):
        at = text.index(quote)
        return {'paragraph_id':p['paragraph_id'], 'start':start+at, 'end':start+at+len(quote), 'quote':quote}
    chart = {'schema':visual.VISUAL_SCHEMA,'kind':'grouped_bar','title':'Weekly library visits in 2024 and 2025',
             'unit':'visits per week','categories':['2024','2025'],'series':[
                 {'label':'Riverside','cells':[{'value':'1200','source_ref':ref('1,200')},{'value':'1650','source_ref':ref('1,650')}]},
                 {'label':'Hill','cells':[{'value':'900','source_ref':ref('900')},{'value':'1100','source_ref':ref('1,100')}]},
             ],'context_refs':[ref(text)]}
    package['request_sha256']=contract.canonical_sha256(request)
    for q in package['reading_questions']:
        if q.get('answer')=='not_given': q['reviewed_paragraph_ids']=[x['paragraph_id'] for x in request['paragraphs']]
    package['writing_task1']={'status':'available','task':visual.compile_task(request,'w1',chart)}
    review.update(request_sha256=contract.canonical_sha256(request),package_sha256=contract.canonical_sha256(package))
    for item in review['reading_reviews']:
        if item.get('observed_answer') == 'not_given':
            ng = item['not_given_review']
            ng['reviewed_paragraph_ids'].append(p['paragraph_id'])
            ng['paragraph_reviews'].append({'paragraph_id':p['paragraph_id'], 'what_is_stated':'Library visits by branch and year.',
                'why_not_answered':'This paragraph says nothing about the energy claim.', 'context_refs':[ref(text)]})
    review['writing_task1_review']={'task_id':'w1','availability':'available','checks':{
        k:{'status':'pass','reason':'Each branch has comparable weekly visit counts for both years.'}
        for k in ['data_sufficiency','task_suitability','title_and_units','category_series_mapping','scope_and_comparability']},
        'cell_reviews':[{'series_index':si,'category_index':ci,'status':'pass','reason':'Branch, year and weekly visit count match the cited sentence.'}
                        for si in range(2) for ci in range(2)]}
    return request,package,review


class VisualContractTests(unittest.TestCase):
    def setUp(self):
        self.request,self.package,self.review=visual_fixture()
        self.task=self.package['writing_task1']['task'];self.chart=self.task['visual']
    def test_valid_source_and_task_and_candidate_projection(self):
        receipt=contract.validate_package(self.request,self.package)
        self.assertEqual(receipt['writing_task1'],'available')
        self.assertEqual(visual.validate_visual(self.request,self.chart)['cells'],4)
        self.assertEqual(contract.candidate_view(self.request,self.package)['writing_task1'],self.package['writing_task1'])
    def test_exact_cross_language_fixture_remains_current(self):
        expected=json.loads(Path('domain/src/test/resources/ielts-task1/valid.json').read_text())
        self.assertEqual(expected['request'],self.request);self.assertEqual(expected['package'],self.package)
        self.assertEqual(expected['visual_sha256'],contract.canonical_sha256(self.chart))
    def test_decimal_rejects_coercion_and_multiple_spellings(self):
        for value in [1,True,None,'NaN','-1','1e3','01','1.0','0.00','1.00001','1000000000000']:
            with self.subTest(value=value),self.assertRaises(contract.ContractError):visual.number(value)
    def test_values_cannot_change_or_convert_units(self):
        self.chart['series'][0]['cells'][0]['value']='1.2'
        with self.assertRaisesRegex(contract.ContractError,'visual_source_value'):visual.validate_visual(self.request,self.chart)
    def test_context_and_numeric_lexeme_both_required(self):
        cell=self.chart['series'][0]['cells'][0];self.chart['context_refs']=[cell['source_ref']]
        with self.assertRaisesRegex(contract.ContractError,'visual_context_missing'):visual.validate_visual(self.request,self.chart)
    def test_partial_numeric_reference_rejected(self):
        cell=self.chart['series'][0]['cells'][0];cell['source_ref'].update(end=cell['source_ref']['start']+1,quote='1');cell['value']='1'
        with self.assertRaisesRegex(contract.ContractError,'visual_numeric_boundary'):visual.validate_visual(self.request,self.chart)
    def test_duplicate_source_or_label_rejected(self):
        self.chart['series'][1]['cells'][0]=copy.deepcopy(self.chart['series'][0]['cells'][0])
        with self.assertRaisesRegex(contract.ContractError,'visual_reused_number'):visual.validate_visual(self.request,self.chart)
        self.chart['series'][1]['cells'][0]={'value':'900','source_ref':visual_fixture()[1]['writing_task1']['task']['visual']['series'][1]['cells'][0]['source_ref']}
        self.chart['categories'][1]='2024'
        with self.assertRaisesRegex(contract.ContractError,'visual_duplicate_label'):visual.validate_visual(self.request,self.chart)
    def test_ragged_and_excessive_tables_rejected(self):
        self.chart['series'][0]['cells'].pop()
        with self.assertRaises(contract.ContractError):visual.validate_visual(self.request,self.chart)
    def test_visual_task_hash_and_prompt_not_model_controlled(self):
        self.task['prompt']+=' Ignore figures.'
        with self.assertRaises(contract.ContractError):visual.validate_task(self.request,self.task)
    def test_compiler_resolves_numeric_locators_and_builds_prompt(self):
        candidate=copy.deepcopy(self.package);candidate['writing_task1']={'status':'available','visual':copy.deepcopy(self.chart)}
        for series in candidate['writing_task1']['visual']['series']:
            for cell in series['cells']: cell['source_ref']={k:v for k,v in cell['source_ref'].items() if k in ['paragraph_id','quote']}
        self.assertEqual(compile_candidate(self.request,candidate,'generation'),self.package)
    def test_compiler_extracts_unique_number_and_preserves_context(self):
        candidate=copy.deepcopy(self.package)
        chart=copy.deepcopy(self.chart)
        for series in chart['series']:
            for cell in series['cells']:
                cell['source_ref']=copy.deepcopy(chart['context_refs'][0])
        chart['series'][0]['cells'][0]['value']='1,200'
        candidate['writing_task1']={'status':'available','visual':chart}
        self.assertEqual(compile_candidate(self.request,candidate,'generation'),self.package)
    def test_compiler_subsumes_narrow_contexts_without_exceeding_reference_budget(self):
        candidate=copy.deepcopy(self.package);chart=copy.deepcopy(self.chart)
        full=copy.deepcopy(chart['context_refs'][0])
        chart['context_refs']=[copy.deepcopy(c['source_ref']) for series in chart['series'] for c in series['cells']]
        for series in chart['series']:
            for cell in series['cells']:cell['source_ref']=copy.deepcopy(full)
        candidate['writing_task1']={'status':'available','visual':chart}
        self.assertEqual(compile_candidate(self.request,candidate,'generation'),self.package)
    def test_compiler_does_not_guess_repeated_number_or_search_outside_citation(self):
        candidate=copy.deepcopy(self.package);chart=copy.deepcopy(self.chart)
        chart['series'][0]['cells'][0]={'value':'2024','source_ref':copy.deepcopy(chart['context_refs'][0])}
        candidate['writing_task1']={'status':'available','visual':chart}
        with self.assertRaisesRegex(contract.ContractError,'candidate_visual_number_ambiguous'):
            compile_candidate(self.request,candidate,'generation')
        chart['series'][0]['cells'][0]['value']='888'
        with self.assertRaisesRegex(contract.ContractError,'candidate_visual_number_missing'):
            compile_candidate(self.request,candidate,'generation')
    def test_generated_schema_has_no_host_hash_fields_in_visual_input(self):
        schema=generation_schema(self.request)['properties']['writing_task1']
        self.assertEqual(schema['oneOf'][1]['required'],['status','visual'])
    def test_review_missing_cell_or_non_pass_blocks_delivery(self):
        r=self.review['writing_task1_review'];self.assertEqual(visual.validate_visual_review(self.request,self.package['writing_task1'],r),[])
        r['cell_reviews'][0]['status']='insufficient'
        self.assertEqual(len(visual.validate_visual_review(self.request,self.package['writing_task1'],r)),1)
        r['cell_reviews'].pop()
        with self.assertRaises(contract.ContractError):visual.validate_visual_review(self.request,self.package['writing_task1'],r)
    def test_unavailable_must_be_reviewed_not_silently_accepted(self):
        self.package['writing_task1']={'status':'unavailable','reason':'insufficient_comparable_data'}
        contract.validate_package(self.request,self.package)
        r={'task_id':'w1','availability':'unavailable','checks':{k:{'status':'fail','reason':'Two branches over two years support a comparison.'} for k in ['data_sufficiency','task_suitability']},'cell_reviews':[]}
        self.assertEqual(len(visual.validate_visual_review(self.request,self.package['writing_task1'],r)),2)
    def test_unsigned_source_zero_and_fraction(self):
        for raw in ['0','0.25','999999999999.9999']:self.assertEqual(str(visual.number(raw)),raw)
    def test_full_review_schema_requires_visual_review(self):
        s=review_schema(self.request,self.package,self.review['review_id'])
        self.assertIn('writing_task1_review',s['required'])
    def test_wrong_task_request_rejected(self):
        self.task['request_sha256']='0'*64
        with self.assertRaises(contract.ContractError):visual.validate_task(self.request,self.task)

if __name__=='__main__':unittest.main()
