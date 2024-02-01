#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>
#include <stdlib.h>

int main(void) {

	FILE* fp;
	fopen_s(&fp, "sglib-combined_16kb_4way_random.out", "r");
	if (fp == NULL) {
		printf("ERROR : Cannot open the file");
		return 1;
	}
	
	int row = 6;	// number of not using text line	from line 1 to line 6
	int col = 170;	// maximum value of number of the not using text in each line

	char** p = (char**)malloc(sizeof(char*) * row);
	for (int i = 0; i < row; i++) {
		p[i] = (char*)malloc(sizeof(char) * col);
	}

	char D;		//	determine if not done by getting first char of the each line
				//  if starting 'a' of 'addr', not done. else, done.

	int T;		//	if not done, T =  1, else T = 0

	int addr;		// addr of HellaCacheReq
	int tag;		// tag of HellaCacheReq
	int cmd;		// cmd of HellaCacheReq
	int size;		// size of HellaCacheReq
	int signed_;	// signed of HellaCacheReq
	int dprv;		// dprv of HellaCacheReq
	int dv;			// dv of HellaCacheReq
	int phys;		// phys of HellaCacheReq
	int no_alloc;	// no_alloc of HellaCacheReq
	int no_xcpt;	// no_xcpt of HellaCacheReq
	int data;		// data of HellaCacheReq
	int mask;		// mast of HellaCacheReq

	int hit;		// if hit, hit = 1. else, hit = 0
	int miss;		// if miss, miss = 1. else, miss = 0

	int hit_count = 0;	// counting hit
	int miss_count = 0;	// counting miss

	float hit_rate;		// hit_rate = hit_count / (hit_count + miss_count)

	// extracting not using text	from line 1 to line 6
	for (int i = 0; i < row; i++) {
		fgets(p[i], col, fp);
	}

	do {
		D = fgetc(fp);
		if (D == 'a') {		// if not done, keep scanning
			T = 1;
			fscanf_s(fp, "ddr  = HellaCacheReq(addr ->%d, tag ->%d, cmd ->%d, size ->%d, signed ->%d, dprv ->%d, dv ->%d, phys ->%d, no_alloc ->%d, no_xcpt ->%d, data ->%d, mask ->%d) ",
				&addr, &tag, &cmd, &size, &signed_, &dprv, &dv, &phys, &no_alloc, &no_xcpt, &data, &mask);
			fscanf_s(fp, "hit   =%d ", &hit);
			fscanf_s(fp, "miss  =%d\n", &miss);

			hit_count += hit;	// counting hit
			miss_count += miss;	// counting miss
		}
		else T = 0;			// if done, exit
	} while (T);

	// calculating hit rate in float type
	hit_rate = 100.0 * hit_count / (hit_count + miss_count);

	printf("Total hit      : %d\nTotal miss     : %d\nTotal hit rate : %f\n", hit_count, miss_count, hit_rate);
	
	fclose(fp);
	return 0;
}
