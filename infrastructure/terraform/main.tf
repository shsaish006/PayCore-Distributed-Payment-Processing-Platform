# Terraform Infrastructure Definition for PayCore on AWS

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# -----------------------------------------------------------------
# Networking: VPC & Subnets (Multi-AZ Deployment)
# -----------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "paycore-vpc"
  cidr = "10.0.0.0/16"

  azs             = ["us-east-1a", "us-east-1b", "us-east-1c"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  enable_nat_gateway   = true
  single_nat_gateway   = false # HA across multiple AZs
  enable_dns_hostnames = true

  tags = {
    Environment = "production"
    Project     = "PayCore"
  }
}

# -----------------------------------------------------------------
# Kubernetes Cluster: EKS
# -----------------------------------------------------------------
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 19.0"

  cluster_name    = "paycore-production-cluster"
  cluster_version = "1.28"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  eks_managed_node_groups = {
    general = {
      min_size     = 3
      max_size     = 10
      desired_size = 3

      instance_types = ["t3.xlarge"] # robust capacity for high concurrency
      capacity_type  = "ON_DEMAND"
    }
  }
}

# -----------------------------------------------------------------
# Databases: Amazon RDS PostgreSQL Cluster (Partitioned transactional storage)
# -----------------------------------------------------------------
resource "aws_db_subnet_group" "db_subnet" {
  name       = "paycore-db-subnet-group"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_db_instance" "postgres" {
  identifier           = "paycore-postgres-db"
  allocated_storage    = 100
  max_allocated_storage = 1000 # Auto-scaling database storage
  engine               = "postgres"
  engine_version       = "15.4"
  instance_class       = "db.r6g.xlarge" # Memory optimized
  db_name              = "paycore_payment"
  username             = "paycore_admin"
  password             = var.db_password
  db_subnet_group_name = aws_db_subnet_group.db_subnet.name
  multi_az             = true # High availability failover
  skip_final_snapshot  = true

  tags = {
    Name = "PayCore Postgres Master"
  }
}

# -----------------------------------------------------------------
# Distributed Cache & Rate Limiting: ElastiCache Redis
# -----------------------------------------------------------------
resource "aws_elasticache_subnet_group" "redis_subnet" {
  name       = "paycore-redis-subnet-group"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id          = "paycore-redis-rg"
  replication_group_description = "PayCore Idempotency & Rate Limit Cache"
  node_type                     = "cache.m6g.large"
  num_cache_clusters            = 2
  parameter_group_name          = "default.redis7"
  port                          = 6379
  subnet_group_name             = aws_elasticache_subnet_group.redis_subnet.name
  automatic_failover_enabled    = true
  multi_az_enabled              = true

  tags = {
    Environment = "production"
  }
}

# -----------------------------------------------------------------
# Messaging Backbone: Managed Streaming for Apache Kafka (MSK)
# -----------------------------------------------------------------
resource "aws_security_group" "kafka_sg" {
  name   = "paycore-kafka-sg"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port   = 9092
    to_port     = 9094
    protocol    = "tcp"
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }
}

resource "aws_msk_cluster" "kafka" {
  cluster_name           = "paycore-kafka-backbone"
  kafka_version          = "3.4.0"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type = "kafka.m5.large"
    client_subnets = module.vpc.private_subnets
    security_groups = [aws_security_group.kafka_sg.id]
    storage_info {
      ebs_storage_info {
        volume_size = 1000 # 1TB storage broker
      }
    }
  }

  tags = {
    Environment = "production"
  }
}

# -----------------------------------------------------------------
# Variables Definitions
# -----------------------------------------------------------------
variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "db_password" {
  type      = string
  sensitive = true
}
