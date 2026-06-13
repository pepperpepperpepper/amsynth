/*
 * amsynth-hzctl.c
 *
 * Small helper to drive amsynth's Hz/gate/velocity OSC control input.
 *
 * Build:
 *   cc tools/amsynth-hzctl.c -o tools/amsynth-hzctl $(pkg-config --cflags --libs liblo)
 *
 * Usage:
 *   tools/amsynth-hzctl -p 9000
 *
 * Input format (whitespace-separated floats):
 *   <hz>
 *   <hz> <gate>
 *   <hz> <gate> <velocity>
 */

#include <lo/lo.h>

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static void usage(const char *argv0)
{
	fprintf(stderr,
			"usage: %s [-h host] [-p port]\n"
			"\n"
			"Reads lines from stdin:\n"
			"  <hz>\n"
			"  <hz> <gate>\n"
			"  <hz> <gate> <velocity>\n",
			argv0);
}

int main(int argc, char **argv)
{
	const char *host = "127.0.0.1";
	const char *port = "9000";

	int opt = 0;
	while ((opt = getopt(argc, argv, "h:p:")) != -1) {
		switch (opt) {
			case 'h': host = optarg; break;
			case 'p': port = optarg; break;
			default:
				usage(argv[0]);
				return 2;
		}
	}

	lo_address addr = lo_address_new(host, port);
	if (!addr) {
		fprintf(stderr, "error: lo_address_new(%s, %s) failed\n", host, port);
		return 1;
	}

	float hz = 0.0f;
	float gate = 0.0f;
	float velocity = 1.0f;

	char line[256];
	while (fgets(line, sizeof(line), stdin)) {
		float hz_in = 0.0f, gate_in = 0.0f, vel_in = 0.0f;
		const int n = sscanf(line, "%f %f %f", &hz_in, &gate_in, &vel_in);
		if (n <= 0)
			continue;

		if (n >= 1) hz = hz_in;
		if (n >= 2) gate = gate_in;
		if (n >= 3) velocity = vel_in;

		lo_send(addr, "/amsynth/hz_input", "fff", hz, gate, velocity);
	}

	lo_address_free(addr);
	return 0;
}

