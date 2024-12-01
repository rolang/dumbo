import dumbo.ResourceFilePath

@main def run(args: String*) =
  val resources = ResourceFilePath.fromResourcesDir("sample_lib").map(_.value).toSet
  val expected = Set(
    "/sample_lib/sub_dir/sub_sub_dir/V4__test_d.sql",
    "/sample_lib/sub_dir/V3__test_c.sql",
    "/sample_lib/V1__test.sql",
    "/sample_lib/V2__test_b.sql",
  )

  if resources == expected then
    println("Test ok")
    sys.exit(0)
  else
    Console.err.println(s"Expected resources $expected\nbut got $resources")
    sys.exit(1)
