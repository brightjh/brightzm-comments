# brightzm-comments
- 项目描述：bright  点评是一个类似于大众点评的个人练手项目，目的是为了提升自己使用 Redis  的熟练度，以及学习 Redis  在实际开发中的使用方法
- 采用技术：SpringBoot、Mybatis-Plus、MySQL、Redis、Docker 
- 项目职责：
1. 为商铺类别等高频访问资源建立 redis  缓存，使页面加载速度由原先的1200ms 降低至254ms
2. 初步解决缓存与数据一致性：对 mysql  数据库进行修改操作后、同时删除 redis  中对应的键值对，让用户在下一次访问中再次从数据库中读取数据写入缓存
3. 初步解决缓存穿透问题：防止数据库中不存在的数据被重复访问，导致缓存中没有相应数据，请求一直发送到数据库中，造成过多的I/O压力；当接收到数据库不存在的数据请求时，在 redis  中存入对应的空值并设置过期时间
4. 初步解决缓存击穿问题：防止高频数据突然过期，导致数据库遭受过高的访问压力；将高频数据设置逻辑过期时间，使缓存中的数据不会过期销毁、当用户请求时判断逻辑过期时间，若过期，则调用线程进行数据更新操作，同时返回过期数据
